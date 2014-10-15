/*
 * Copyright 2014 Higher Frequency Trading http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.map;

import net.openhft.chronicle.ChronicleHashErrorListener;
import net.openhft.chronicle.map.serialization.*;
import net.openhft.chronicle.map.threadlocal.Provider;
import net.openhft.chronicle.map.threadlocal.ThreadLocalCopies;
import net.openhft.lang.Maths;
import net.openhft.lang.collection.DirectBitSet;
import net.openhft.lang.collection.SingleThreadedDirectBitSet;
import net.openhft.lang.io.*;
import net.openhft.lang.io.serialization.JDKObjectSerializer;
import net.openhft.lang.io.serialization.ObjectFactory;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import net.openhft.lang.model.Byteable;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.currentThread;


class VanillaChronicleMap<K, KI, MKI extends MetaBytesInterop<K, KI>,
        V, VW, MVW extends MetaBytesWriter<V, VW>> extends AbstractMap<K, V>
        implements ChronicleMap<K, V>, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(VanillaChronicleMap.class);

    /**
     * Because DirectBitSet implementations couldn't find more than 64 continuous clear or set bits.
     */
    static final int MAX_ENTRY_OVERSIZE_FACTOR = 64;

    private static final int SEGMENT_HEADER = 64;
    final Class<K> kClass;
    final SizeMarshaller keySizeMarshaller;
    final BytesReader<K> originalKeyReader;
    transient Provider<BytesReader<K>> keyReaderProvider;
    final KI originalKeyInterop;
    transient Provider<KI> keyInteropProvider;
    final MKI originalMetaKeyInterop;
    final MetaProvider<K, KI, MKI> metaKeyInteropProvider;

    final Class<V> vClass;
    final SizeMarshaller valueSizeMarshaller;
    final BytesReader<V> originalValueReader;
    transient Provider<BytesReader<V>> valueReaderProvider;
    final VW originalValueWriter;
    transient Provider<VW> valueWriterProvider;
    final MVW originalMetaValueWriter;
    final MetaProvider<V, VW, MVW> metaValueWriterProvider;
    final ObjectFactory<V> valueFactory;
    final DefaultValueProvider<K, V> defaultValueProvider;

    final int metaDataBytes;
    //   private final int replicas;
    final int entrySize;
    final Alignment alignment;
    final int actualSegments;
    final int entriesPerSegment;
    final MapEventListener<K, V, ChronicleMap<K, V>> eventListener;
    // if set the ReturnsNull fields will cause some functions to return NULL
    // rather than as returning the Object can be expensive for something you probably don't use.
    final boolean putReturnsNull;
    final boolean removeReturnsNull;

    private final long lockTimeOutNS;
    private final ChronicleHashErrorListener errorListener;
    transient Segment[] segments; // non-final for close()
    // non-final for close() and because it is initialized out of constructor
    transient BytesStore ms;
    transient long headerSize;
    transient Set<Map.Entry<K, V>> entrySet;

    private int bits;
    private int mask;

    public VanillaChronicleMap(ChronicleMapBuilder<K, V> builder) throws IOException {

        SerializationBuilder<K> keyBuilder = builder.keyBuilder;
        kClass = keyBuilder.eClass;
        keySizeMarshaller = keyBuilder.sizeMarshaller();
        originalKeyReader = keyBuilder.reader();

        originalKeyInterop = (KI) keyBuilder.interop();

        originalMetaKeyInterop = (MKI) keyBuilder.metaInterop();
        metaKeyInteropProvider = (MetaProvider<K, KI, MKI>) keyBuilder.metaInteropProvider();

        SerializationBuilder<V> valueBuilder = builder.valueBuilder;
        vClass = valueBuilder.eClass;
        valueSizeMarshaller = valueBuilder.sizeMarshaller();
        originalValueReader = valueBuilder.reader();

        originalValueWriter = (VW) valueBuilder.interop();
        originalMetaValueWriter = (MVW) valueBuilder.metaInterop();
        metaValueWriterProvider = (MetaProvider) valueBuilder.metaInteropProvider();
        valueFactory = valueBuilder.factory();
        defaultValueProvider = builder.defaultValueProvider();

        lockTimeOutNS = builder.lockTimeOut(TimeUnit.NANOSECONDS);

        //  this.replicas = builder.replicas();
        this.entrySize = builder.entrySize();
        this.alignment = builder.entryAndValueAlignment();

        this.errorListener = builder.errorListener();
        this.putReturnsNull = builder.putReturnsNull();
        this.removeReturnsNull = builder.removeReturnsNull();

        this.actualSegments = builder.actualSegments();
        // align by 8 because otherwise sizeOfBitSets() might address less blocks than specified
        this.entriesPerSegment = align8(builder.actualEntriesPerSegment());
        this.metaDataBytes = builder.metaDataBytes();
        this.eventListener = builder.eventListener();

        this.mask = useSmallMultiMaps() ? 0xFFFF : ~0;
        this.bits = Maths.intLog2(actualSegments);

        initTransients();
    }

    int segmentHash(long hash) {
        return (int) (hash >>> bits) & mask;
    }

    int getSegment(long hash) {
        return (int) (hash & (actualSegments - 1));
    }

    /**
     * Cache line alignment, assuming 64-byte cache lines.
     */
    static long align64(long l) {
        return (l + 63) & ~63;
    }

    static int align8(int n) {
        return (n + 7) & ~7;
    }

    void initTransients() {
        keyReaderProvider = Provider.of((Class) originalKeyReader.getClass());
        keyInteropProvider = Provider.of((Class) originalKeyInterop.getClass());

        valueReaderProvider = Provider.of((Class) originalValueReader.getClass());
        valueWriterProvider = Provider.of((Class) originalValueWriter.getClass());

        if (defaultValueProvider instanceof ConstantValueProvider) {
            ConstantValueProvider<K, V> constantValueProvider =
                    (ConstantValueProvider<K, V>) defaultValueProvider;
            if (constantValueProvider.wasDeserialized()) {
                ThreadLocalCopies copies = valueReaderProvider.getCopies(null);
                BytesReader<V> valueReader = valueReaderProvider.get(copies, originalValueReader);
                constantValueProvider.initTransients(valueReader);
            }
        }

        @SuppressWarnings("unchecked")
        Segment[] ss = (Segment[]) Array.newInstance(segmentType(), actualSegments);
        this.segments = ss;
    }

    Class segmentType() {
        return Segment.class;
    }

    long createMappedStoreAndSegments(BytesStore bytesStore) throws IOException {
        this.ms = bytesStore;

        onHeaderCreated();

        long offset = getHeaderSize();
        long segmentSize = segmentSize();
        for (int i = 0; i < this.segments.length; i++) {
            this.segments[i] = createSegment((NativeBytes) ms.bytes(offset, segmentSize), i);
            offset += segmentSize;
        }
        return offset;
    }

    long createMappedStoreAndSegments(File file) throws IOException {
        return createMappedStoreAndSegments(new MappedStore(file, FileChannel.MapMode.READ_WRITE,
                sizeInBytes(), JDKObjectSerializer.INSTANCE));
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initTransients();
    }

    /**
     * called when the header is created
     */
    void onHeaderCreated() {

    }

    long getHeaderSize() {
        return headerSize;
    }

    Segment createSegment(NativeBytes bytes, int index) {
        return new Segment(bytes, index);
    }

    @Override
    public File file() {
        return ms.file();
    }

    long sizeInBytes() {
        return getHeaderSize() + segments.length * segmentSize();
    }

    long sizeOfMultiMap() {
        return useSmallMultiMaps() ?
                VanillaShortShortMultiMap.sizeInBytes(entriesPerSegment) :
                VanillaIntIntMultiMap.sizeInBytes(entriesPerSegment);
    }

    long sizeOfMultiMapBitSet() {
        return useSmallMultiMaps() ?
                VanillaShortShortMultiMap.sizeOfBitSetInBytes(entriesPerSegment) :
                VanillaIntIntMultiMap.sizeOfBitSetInBytes(entriesPerSegment);
    }

    boolean useSmallMultiMaps() {
        return entriesPerSegment <= (1 << 16);
    }

    long sizeOfBitSets() {
        return align64(entriesPerSegment / 8);
    }

    int numberOfBitSets() {
        return 1; // for free list
        //  + (replicas > 0 ? 1 : 0) // deleted set
        //   + replicas; // to notify each replica of a change.
    }

    long segmentSize() {
        long ss = SEGMENT_HEADER
                + align64(sizeOfMultiMap() + sizeOfMultiMapBitSet()) * multiMapsPerSegment()
                + numberOfBitSets() * sizeOfBitSets() // the free list and 0+ dirty lists.
                + sizeOfEntriesInSegment();
        if ((ss & 63) != 0)
            throw new AssertionError();

        // Say, there is 32 KB L1 cache with 2(4, 8) way set associativity, 64-byte lines.
        // It means there are 32 * 1024 / 64 / 2(4, 8) = 256(128, 64) sets,
        // i. e. each way (bank) contains 256(128, 64) lines. (L2 and L3 caches has more sets.)
        // If segment size in lines multiplied by 2^n is divisible by set size,
        // every 2^n-th segment header fall into the same set.
        // To break this up we make segment size odd in lines, in this case only each
        // 256(128, 64)-th segment header fall into the same set.

        // If there are 64 sets in L1, it should be 8- or much less likely 4-way, and segments
        // collision by pairs is not so terrible.

        // if the size is a multiple of 4096 or slightly more. Make sure it is at least 64 more than a multiple.
        if ((ss & 4093) < 64)
            ss = (ss & ~63) + 64;

        return ss;
    }

    int multiMapsPerSegment() {
        return 1;
    }

    private long sizeOfEntriesInSegment() {
        return align64((long) entriesPerSegment * entrySize);
    }

    @Override
    public void close() {
        if (ms == null)
            return;
        ms.free();
        segments = null;
        ms = null;
    }

    MapEventListener<K, V, ChronicleMap<K, V>> eventListener() {
        return eventListener;
    }

    void checkKey(Object key) {
        if (!kClass.isInstance(key)) {
            // key.getClass will cause NPE exactly as needed
            throw new ClassCastException("Key must be a " + kClass.getName() +
                    " but was a " + key.getClass());
        }
    }

    void checkValue(Object value) {
        if (vClass != Void.class && !vClass.isInstance(value)) {
            throw new ClassCastException("Value must be a " + vClass.getName() +
                    " but was a " + value.getClass());
        }
    }

    @Override
    public V put(K key, V value) {
        return put0(key, value, true);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return put0(key, value, false);
    }

    private V put0(K key, V value, boolean replaceIfPresent) {
        checkKey(key);
        checkValue(value);
        ThreadLocalCopies copies = keyInteropProvider.getCopies(null);
        KI keyInterop = keyInteropProvider.get(copies, originalKeyInterop);
        copies = metaKeyInteropProvider.getCopies(copies);
        MKI metaKeyInterop =
                metaKeyInteropProvider.get(copies, originalMetaKeyInterop, keyInterop, key);
        long hash = metaKeyInterop.hash(keyInterop, key);
        int segmentNum = getSegment(hash);
        int segmentHash = segmentHash(hash);
        return segments[segmentNum].put(copies, metaKeyInterop, keyInterop, key, value, segmentHash,
                replaceIfPresent);
    }

    @Override
    public V get(Object key) {
        return lookupUsing((K) key, null, false);
    }

    @Override
    public V getUsing(K key, V value) {
        return lookupUsing(key, value, false);
    }

    @Override
    public V acquireUsing(K key, V value) {
        return lookupUsing(key, value, true);
    }

    V lookupUsing(K key, V value, boolean create) {
        checkKey(key);
        ThreadLocalCopies copies = keyInteropProvider.getCopies(null);
        KI keyInterop = keyInteropProvider.get(copies, originalKeyInterop);
        copies = metaKeyInteropProvider.getCopies(copies);
        MKI metaKeyInterop =
                metaKeyInteropProvider.get(copies, originalMetaKeyInterop, keyInterop, key);
        long hash = metaKeyInterop.hash(keyInterop, key);
        int segmentNum = getSegment(hash);
        int segmentHash = segmentHash(hash);
        return segments[segmentNum].acquire(copies, metaKeyInterop, keyInterop, key, value,
                segmentHash, create);
    }

    @Override
    public boolean containsKey(final Object k) {
        checkKey(k);
        K key = (K) k;
        ThreadLocalCopies copies = keyInteropProvider.getCopies(null);
        KI keyInterop = keyInteropProvider.get(copies, originalKeyInterop);
        copies = metaKeyInteropProvider.getCopies(copies);
        MKI metaKeyInterop =
                metaKeyInteropProvider.get(copies, originalMetaKeyInterop, keyInterop, key);
        long hash = metaKeyInterop.hash(keyInterop, key);
        int segmentNum = getSegment(hash);
        int segmentHash = segmentHash(hash);
        return segments[segmentNum].containsKey(keyInterop, metaKeyInterop, key, segmentHash);
    }

    @Override
    public void clear() {
        for (Segment segment : segments)
            segment.clear();
    }

    @NotNull
    @Override
    public Set<Entry<K, V>> entrySet() {
        return (entrySet != null) ? entrySet : (entrySet = new EntrySet());
    }


    /**
     * @throws NullPointerException if the specified key is null
     */
    @Override
    public V remove(final Object key) {
        return removeIfValueIs(key, null);
    }

    /**
     * @throws NullPointerException if the specified key is null
     */
    @Override
    public boolean remove(final Object key, final Object value) {
        if (value == null)
            return false; // CHM compatibility; I would throw NPE
        return removeIfValueIs(key, (V) value) != null;
    }


    /**
     * removes ( if there exists ) an entry from the map, if the {@param key} and {@param expectedValue} match
     * that of a maps.entry. If the {@param expectedValue} equals null then ( if there exists ) an entry whose
     * key equals {@param key} this is removed.
     *
     * @param k             the key of the entry to remove
     * @param expectedValue null if not required
     * @return true if and entry was removed
     */
    V removeIfValueIs(final Object k, final V expectedValue) {
        checkKey(k);
        K key = (K) k;
        ThreadLocalCopies copies = keyInteropProvider.getCopies(null);
        KI keyInterop = keyInteropProvider.get(copies, originalKeyInterop);
        copies = metaKeyInteropProvider.getCopies(copies);
        MKI metaKeyInterop =
                metaKeyInteropProvider.get(copies, originalMetaKeyInterop, keyInterop, key);
        long hash = metaKeyInterop.hash(keyInterop, key);
        int segmentNum = getSegment(hash);
        int segmentHash = segmentHash(hash);
        return segments[segmentNum].remove(copies, metaKeyInterop, keyInterop, key, expectedValue,
                segmentHash);
    }

    /**
     * @throws NullPointerException if any of the arguments are null
     */
    @Override
    public boolean replace(final K key, final V oldValue, final V newValue) {
        checkValue(oldValue);
        return oldValue.equals(replaceIfValueIs(key, oldValue, newValue));
    }


    /**
     * @throws NullPointerException if the specified key or value is null
     */
    @Override
    public V replace(final K key, final V value) {
        return replaceIfValueIs(key, null, value);
    }

    @Override
    public long longSize() {
        long result = 0;

        for (final Segment segment : this.segments) {
            result += segment.getSize();
        }

        return result;
    }

    @Override
    public int size() {
        long size = longSize();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    /**
     * replace the value in a map, only if the existing entry equals {@param existingValue}
     *
     * @param key           the key into the map
     * @param existingValue the expected existing value in the map ( could be null when we don't wish to do
     *                      this check )
     * @param newValue      the new value you wish to store in the map
     * @return the value that was replaced
     */
    V replaceIfValueIs(@net.openhft.lang.model.constraints.NotNull final K key, final V existingValue,
                       final V newValue) {
        checkKey(key);
        checkValue(newValue);
        ThreadLocalCopies copies = keyInteropProvider.getCopies(null);
        KI keyInterop = keyInteropProvider.get(copies, originalKeyInterop);
        copies = metaKeyInteropProvider.getCopies(copies);
        MKI metaKeyInterop =
                metaKeyInteropProvider.get(copies, originalMetaKeyInterop, keyInterop, key);
        long hash = metaKeyInterop.hash(keyInterop, key);
        int segmentNum = getSegment(hash);
        int segmentHash = segmentHash(hash);
        return segments[segmentNum].replace(copies, metaKeyInterop, keyInterop, key, existingValue,
                newValue, segmentHash);
    }

    /**
     * For testing
     */
    void checkConsistency() {
        for (Segment segment : segments) {
            segment.checkConsistency();
        }
    }

    long readValueSize(Bytes entry) {
        long valueSize = valueSizeMarshaller.readSize(entry);
        alignment.alignPositionAddr(entry);
        return valueSize;
    }

    // these methods should be package local, not public or private.
    class Segment implements SharedSegment {
        /*
        The entry format is
        - encoded length for key
        - bytes for the key
        - [possible alignment]
        - encoded length of the value
        - bytes for the value.
         */
        static final int LOCK_OFFSET = 0; // 64-bit
        static final int SIZE_OFFSET = LOCK_OFFSET + 8; // 32-bit
        static final int PAD1_OFFSET = SIZE_OFFSET + 4; // 32-bit
        static final int REPLICA_OFFSET = PAD1_OFFSET + 4; // 64-bit

        final NativeBytes bytes;
        final MultiStoreBytes tmpBytes = new MultiStoreBytes();
        final long entriesOffset;
        private final int index;
        private final SingleThreadedDirectBitSet freeList;
        private IntIntMultiMap hashLookup;
        private int nextPosToSearchFrom = 0;


        /**
         * @param bytes
         * @param index the index of this segment held by the map
         */
        Segment(NativeBytes bytes, int index) {
            this.bytes = bytes;
            this.index = index;

            long start = bytes.startAddr() + SEGMENT_HEADER;
            createHashLookups(start);
            start += align64(sizeOfMultiMap() + sizeOfMultiMapBitSet()) * multiMapsPerSegment();
            final NativeBytes bsBytes = new NativeBytes(tmpBytes.objectSerializer(),
                    start, start + ((entriesPerSegment + 7) / 8), null);
            freeList = new SingleThreadedDirectBitSet(bsBytes);
            start += numberOfBitSets() * sizeOfBitSets();
            entriesOffset = start - bytes.startAddr();
            assert bytes.capacity() >= entriesOffset + entriesPerSegment * entrySize;
        }

        void createHashLookups(long start) {
            hashLookup = createMultiMap(start);
        }

        public IntIntMultiMap getHashLookup() {
            return hashLookup;
        }


        IntIntMultiMap createMultiMap(long start) {
            final NativeBytes multiMapBytes =
                    new NativeBytes(new VanillaBytesMarshallerFactory(), start,
                            start = start + sizeOfMultiMap(), null);

            final NativeBytes sizeOfMultiMapBitSetBytes =
                    new NativeBytes(new VanillaBytesMarshallerFactory(), start,
                            start + sizeOfMultiMapBitSet(), null);
            multiMapBytes.load();
            return useSmallMultiMaps() ?
                    new VanillaShortShortMultiMap(multiMapBytes, sizeOfMultiMapBitSetBytes) :
                    new VanillaIntIntMultiMap(multiMapBytes, sizeOfMultiMapBitSetBytes);
        }

        public int getIndex() {
            return index;
        }


        /* Methods with private access modifier considered private to Segment
         * class, although Java allows to access them from outer class anyway.
         */

        /**
         * increments the size by one
         */
        void incrementSize() {
            this.bytes.addInt(SIZE_OFFSET, 1);
        }

        void resetSize() {
            this.bytes.writeInt(SIZE_OFFSET, 0);
        }

        /**
         * decrements the size by one
         */
        void decrementSize() {
            this.bytes.addInt(SIZE_OFFSET, -1);
        }

        /**
         * reads the the number of entries in this segment
         */
        int getSize() {
            // any negative value is in error state.
            return Math.max(0, this.bytes.readVolatileInt(SIZE_OFFSET));
        }


        public void lock() throws IllegalStateException {
            while (true) {
                final boolean success = bytes.tryLockNanosLong(LOCK_OFFSET, lockTimeOutNS);
                if (success) return;
                if (currentThread().isInterrupted()) {
                    throw new IllegalStateException(new InterruptedException("Unable to obtain lock, interrupted"));
                } else {
                    errorListener.onLockTimeout(bytes.threadIdForLockLong(LOCK_OFFSET));
                    bytes.resetLockLong(LOCK_OFFSET);
                }
            }
        }

        public void unlock() {
            try {
                bytes.unlockLong(LOCK_OFFSET);
            } catch (IllegalMonitorStateException e) {
                errorListener.errorOnUnlock(e);
            }
        }

        public long offsetFromPos(long pos) {
            return entriesOffset + pos * entrySize;
        }

        long posFromOffset(long offset) {
            return (offset - entriesOffset) / entrySize;
        }


        public MultiStoreBytes entry(long offset) {
            return reuse(tmpBytes, offset);
        }

        private MultiStoreBytes reuse(MultiStoreBytes entry, long offset) {
            offset += metaDataBytes;
            entry.storePositionAndSize(bytes, offset,
                    // "Infinity". Limit not used when treating entries as
                    // possibly oversized
                    bytes.limit() - offset);
            return entry;
        }

        long entryStartAddr(long offset) {
            // entry.address() points to "needed" start addr + metaDataBytes
            return bytes.startAddr() + offset;
        }

        private long entrySize(long keySize, long valueSize) {
            return alignment.alignAddr(metaDataBytes +
                    keySizeMarshaller.sizeEncodingSize(keySize) + keySize +
                    valueSizeMarshaller.sizeEncodingSize(valueSize)) + valueSize;
        }

        int inBlocks(long sizeInBytes) {
            if (sizeInBytes <= entrySize)
                return 1;
            // int division is MUCH faster than long on Intel CPUs
            sizeInBytes -= 1;
            if (sizeInBytes <= Integer.MAX_VALUE)
                return (((int) sizeInBytes) / entrySize) + 1;
            return (int) (sizeInBytes / entrySize) + 1;
        }

        /**
         * Used to acquire an object of type V from the Segment. <p/> {@code usingValue} is reused to read the
         * value if key is present in this Segment, if key is absent in this Segment: <p/> <ol><li>If {@code
         * create == false}, just {@code null} is returned (except when event listener provides a value "on
         * get missing" - then it is put into this Segment for the key).</li> <p/> <li>If {@code create ==
         * true}, {@code usingValue} or a newly created instance of value class, if {@code usingValue ==
         * null}, is put into this Segment for the key.</li></ol>
         *
         * @param hash2 a hash code related to the {@code keyBytes}
         * @return the value which is finally associated with the given key in this Segment after execution of
         * this method, or {@code null}.
         */
        V acquire(ThreadLocalCopies copies, MKI metaKeyInterop, KI keyInterop, K key, V usingValue,
                  int hash2, boolean create) {
            lock();
            try {
                long keySize = metaKeyInterop.size(keyInterop, key);
                MultiStoreBytes entry = tmpBytes;
                long offset = searchKey(keyInterop, metaKeyInterop, key, keySize, hash2, entry,
                        hashLookup);
                if (offset >= 0) {
                    return onKeyPresentOnAcquire(copies, key, usingValue, offset, entry);
                } else {
                    boolean usingValuePassed = usingValue != null;
                    usingValue = tryObtainUsingValueOnAcquire(key, usingValue, create);
                    if (usingValue != null) {
                        // If `create` is false, this method was called from get() or getUsing()
                        // and non-null `usingValue` was returned by notifyMissed() method.
                        // This "missed" default value is considered as genuine value
                        // rather than "using" container to fill up, even if it implements Byteable.
                        offset = putEntry(copies, metaKeyInterop, keyInterop, key, keySize,
                                usingValue, create);
                        incrementSize();
                        if (usingValuePassed || !create)
                            notifyPut(offset, true, key, usingValue, posFromOffset(offset));
                        return usingValue;
                    } else {
                        return null;
                    }
                }
            } finally {
                unlock();
            }
        }

        long searchKey(KI keyInterop, MKI metaKeyInterop, K key, long keySize, int hash2,
                       MultiStoreBytes entry, IntIntMultiMap hashLookup) {
            hashLookup.startSearch(hash2);
            for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                long offset = offsetFromPos(pos);
                reuse(entry, offset);
                if (!keyEquals(keyInterop, metaKeyInterop, key, keySize, entry))
                    continue;
                // key is found
                entry.skip(keySize);
                return offset;
            }
            // key is not found
            return -1L;
        }

        V onKeyPresentOnAcquire(ThreadLocalCopies copies, K key, V usingValue, long offset,
                                NativeBytes entry) {
            V v = readValue(copies, entry, usingValue);
            notifyGet(offset, key, v);
            return v;
        }

        V tryObtainUsingValueOnAcquire(K key, V usingValue, boolean create) {
            if (create) {
                if (usingValue != null) {
                    return usingValue;
                } else {
                    try {
                        usingValue = valueFactory.create();
                        if (usingValue == null) {
                            throw new IllegalStateException("acquireUsing() method requires" +
                                    "valueFactory.create() result to be non-null. " +
                                    "By default it is so when value class is" +
                                    "a Byteable/BytesMarshallable/Externalizable subclass." +
                                    "Note that acquireUsing() anyway makes very little sense " +
                                    "when value class is not a Byteable subclass.");
                        }
                        return usingValue;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            } else {
                if (usingValue instanceof Byteable)
                    ((Byteable) usingValue).bytes(null, 0);
                return defaultValueProvider.get(key, usingValue);
            }
        }

        V put(ThreadLocalCopies copies, MKI metaKeyInterop, KI keyInterop, K key, V value,
              int hash2, boolean replaceIfPresent) {
            lock();
            try {
                long keySize = metaKeyInterop.size(keyInterop, key);
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    long offset = offsetFromPos(pos);
                    NativeBytes entry = entry(offset);
                    if (!keyEquals(keyInterop, metaKeyInterop, key, keySize, entry))
                        continue;
                    // key is found
                    entry.skip(keySize);
                    if (replaceIfPresent) {
                        return replaceValueOnPut(copies, key, value, entry, pos, offset,
                                !putReturnsNull, hashLookup);
                    } else {
                        return putReturnsNull ? null : readValue(copies, entry, null);
                    }
                }
                // key is not found
                long offset = putEntry(copies, metaKeyInterop, keyInterop, key, keySize, value,
                        false);
                incrementSize();
                notifyPut(offset, true, key, value, posFromOffset(offset));
                return null;
            } finally {
                unlock();
            }
        }

        V replaceValueOnPut(ThreadLocalCopies copies, K key, V value, NativeBytes entry, int pos,
                            long offset, boolean readPrevValue, IntIntMultiMap searchedHashLookup) {
            long valueSizePos = entry.position();
            long valueSize = readValueSize(entry);
            long entryEndAddr = entry.positionAddr() + valueSize;
            V prevValue = null;
            if (readPrevValue)
                prevValue = readValue(copies, entry, null, valueSize);

            // putValue may relocate entry and change offset
            offset = putValue(pos, offset, entry, valueSizePos, entryEndAddr, copies, value, null,
                    searchedHashLookup);
            notifyPut(offset, false, key, value, posFromOffset(offset));
            return prevValue;
        }

        /**
         * Puts entry. If {@code value} implements {@link Byteable} interface and {@code usingValue} is {@code
         * true}, the value is backed with the bytes of this entry.
         *
         * @param value      the value to put
         * @param usingValue {@code true} if the value should be backed with the bytes of the entry, if it
         *                   implements {@link Byteable} interface, {@code false} if it should put itself
         * @return offset of the written entry in the Segment bytes
         */
        private long putEntry(ThreadLocalCopies copies, MKI metaKeyInterop, KI keyInterop, K key,
                              long keySize, V value, boolean usingValue) {

            // "if-else polymorphism" is not very beautiful, but allows to
            // reuse the rest code of this method and doesn't hurt performance.
            boolean byteableValue = usingValue && value instanceof Byteable;
            long valueSize;
            MetaBytesWriter<V, VW> metaValueWriter = null;
            VW valueWriter = null;
            Byteable valueAsByteable = null;
            if (!byteableValue) {
                copies = valueWriterProvider.getCopies(copies);
                valueWriter = valueWriterProvider.get(copies, originalValueWriter);
                copies = metaValueWriterProvider.getCopies(copies);
                metaValueWriter = metaValueWriterProvider.get(
                        copies, originalMetaValueWriter, valueWriter, value);
                valueSize = metaValueWriter.size(valueWriter, value);
            } else {
                valueAsByteable = (Byteable) value;
                valueSize = valueAsByteable.maxSize();
            }

            long entrySize = entrySize(keySize, valueSize);
            int pos = alloc(inBlocks(entrySize));
            long offset = offsetFromPos(pos);
            clearMetaData(offset);
            NativeBytes entry = entry(offset);

            keySizeMarshaller.writeSize(entry, keySize);
            metaKeyInterop.write(keyInterop, entry, key);

            writeValueOnPutEntry(valueSize, metaValueWriter, valueWriter, value, valueAsByteable,
                    entry);
            hashLookup.putAfterFailedSearch(pos);
            return offset;
        }

        void writeValueOnPutEntry(long valueSize, MetaBytesWriter<V, VW> metaValueWriter,
                                  VW valueWriter, V value, Byteable valueAsByteable,
                                  NativeBytes entry) {
            valueSizeMarshaller.writeSize(entry, valueSize);
            alignment.alignPositionAddr(entry);

            if (metaValueWriter != null) {
                metaValueWriter.write(valueWriter, entry, value);
            } else {
                assert valueAsByteable != null;
                long valueOffset = entry.positionAddr() - bytes.address();
                bytes.zeroOut(valueOffset, valueOffset + valueSize);
                valueAsByteable.bytes(bytes, valueOffset);
            }
        }

        void clearMetaData(long offset) {
            if (metaDataBytes > 0)
                bytes.zeroOut(offset, offset + metaDataBytes);
        }

        int alloc(int blocks) {
            if (blocks > MAX_ENTRY_OVERSIZE_FACTOR)
                throw new IllegalArgumentException("Entry is too large: requires " + blocks +
                        " entry size chucks, " + MAX_ENTRY_OVERSIZE_FACTOR + " is maximum.");
            int ret = (int) freeList.setNextNContinuousClearBits(nextPosToSearchFrom,
                    blocks);
            if (ret == DirectBitSet.NOT_FOUND) {
                ret = (int) freeList.setNextNContinuousClearBits(0, blocks);
                if (ret == DirectBitSet.NOT_FOUND) {
                    if (blocks == 1) {
                        throw new IllegalArgumentException(
                                "Segment is full, no free entries found");
                    } else {
                        throw new IllegalArgumentException(
                                "Segment is full or has no ranges of " + blocks
                                        + " continuous free blocks"
                        );
                    }
                }
                updateNextPosToSearchFrom(ret, blocks);
            } else {
                // if bit at nextPosToSearchFrom is clear, it was skipped because
                // more than 1 block was requested. Don't move nextPosToSearchFrom
                // in this case. blocks == 1 clause is just a fast path.
                if (blocks == 1 || freeList.isSet(nextPosToSearchFrom)) {
                    updateNextPosToSearchFrom(ret, blocks);
                }
            }
            return ret;
        }

        private void updateNextPosToSearchFrom(int allocated, int blocks) {
            if ((nextPosToSearchFrom = allocated + blocks) >= freeList.size())
                nextPosToSearchFrom = 0;
        }

        private boolean realloc(int fromPos, int oldBlocks, int newBlocks) {
            if (freeList.allClear(fromPos + oldBlocks, fromPos + newBlocks)) {
                freeList.set(fromPos + oldBlocks, fromPos + newBlocks);
                return true;
            } else {
                return false;
            }
        }

        void free(int fromPos, int blocks) {
            freeList.clear(fromPos, fromPos + blocks);
            if (fromPos < nextPosToSearchFrom)
                nextPosToSearchFrom = fromPos;
        }

        V readValue(ThreadLocalCopies copies, NativeBytes entry, V value) {
            return readValue(copies, entry, value, readValueSize(entry));
        }

        V readValue(ThreadLocalCopies copies, NativeBytes entry, V value, long valueSize) {
            copies = valueReaderProvider.getCopies(copies);
            BytesReader<V> valueReader = valueReaderProvider.get(copies, originalValueReader);
            bytes.positionAddr(entry.positionAddr());
            return valueReader.read(bytes, valueSize, value);
        }

        boolean keyEquals(KI keyInterop, MKI metaKeyInterop, K key,
                          long keySize, Bytes entry) {
            return keySize == keySizeMarshaller.readSize(entry) &&
                    metaKeyInterop.startsWith(keyInterop, entry, key);
        }

        /**
         * Removes a key (or key-value pair) from the Segment. <p/> The entry will only be removed if {@code
         * expectedValue} equals to {@code null} or the value previously corresponding to the specified key.
         *
         * @param hash2 a hash code related to the {@code keyBytes}
         * @return the value of the entry that was removed if the entry corresponding to the {@code keyBytes}
         * exists and {@link #removeReturnsNull} is {@code false}, {@code null} otherwise
         */
        V remove(ThreadLocalCopies copies, MKI metaKeyInterop, KI keyInterop, K key,
                 V expectedValue, int hash2) {
            lock();
            try {
                long keySize = metaKeyInterop.size(keyInterop, key);
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    long offset = offsetFromPos(pos);
                    NativeBytes entry = entry(offset);
                    if (!keyEquals(keyInterop, metaKeyInterop, key, keySize, entry))
                        continue;
                    // key is found
                    entry.skip(keySize);
                    long valueSize = readValueSize(entry);
                    long entryEndAddr = entry.positionAddr() + valueSize;
                    V valueRemoved = expectedValue != null || !removeReturnsNull
                            ? readValue(copies, entry, null, valueSize) : null;
                    if (expectedValue != null && !expectedValue.equals(valueRemoved))
                        return null;
                    hashLookup.removePrevPos();
                    decrementSize();
                    free(pos, inBlocks(entryEndAddr - entryStartAddr(offset)));
                    notifyRemoved(offset, key, valueRemoved, pos);
                    return valueRemoved;
                }
                // key is not found
                return null;
            } finally {
                unlock();
            }
        }

        boolean containsKey(KI keyInterop, MKI metaKeyInterop, K key, int hash2) {
            lock();
            try {
                long keySize = metaKeyInterop.size(keyInterop, key);
                IntIntMultiMap hashLookup = containsKeyHashLookup();
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    Bytes entry = entry(offsetFromPos(pos));
                    if (keyEquals(keyInterop, metaKeyInterop, key, keySize, entry))
                        return true;
                }
                return false;
            } finally {
                unlock();
            }
        }

        IntIntMultiMap containsKeyHashLookup() {
            return hashLookup;
        }

        /**
         * Replaces the specified value for the key with the given value. <p/> {@code newValue} is set only if
         * the existing value corresponding to the specified key is equal to {@code expectedValue} or {@code
         * expectedValue == null}.
         *
         * @param hash2 a hash code related to the {@code keyBytes}
         * @return the replaced value or {@code null} if the value was not replaced
         */
        V replace(ThreadLocalCopies copies, MKI metaKeyInterop, KI keyInterop, K key,
                  V expectedValue, V newValue, int hash2) {
            lock();
            try {
                long keySize = metaKeyInterop.size(keyInterop, key);
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    long offset = offsetFromPos(pos);
                    NativeBytes entry = entry(offset);
                    if (!keyEquals(keyInterop, metaKeyInterop, key, keySize, entry))
                        continue;
                    // key is found
                    entry.skip(keySize);
                    return onKeyPresentOnReplace(copies, key, expectedValue, newValue,
                            pos, offset, entry, hashLookup);
                }
                // key is not found
                return null;
            } finally {
                unlock();
            }
        }

        V onKeyPresentOnReplace(ThreadLocalCopies copies, K key, V expectedValue, V newValue,
                                int pos, long offset, NativeBytes entry,
                                IntIntMultiMap searchedHashLookup) {
            long valueSizePos = entry.position();
            long valueSize = readValueSize(entry);
            long entryEndAddr = entry.positionAddr() + valueSize;
            V valueRead = readValue(copies, entry, null, valueSize);
            if (valueRead == null)
                return null;
            if (expectedValue == null || expectedValue.equals(valueRead)) {
                // putValue may relocate entry and change offset
                offset = putValue(pos, offset, entry, valueSizePos, entryEndAddr, copies, newValue,
                        null, searchedHashLookup);
                notifyPut(offset, false, key, newValue,
                        posFromOffset(offset));
                return valueRead;
            }
            return null;
        }


        void notifyPut(long offset, boolean added, K key, V value, final long pos) {
            if (eventListener() != MapEventListeners.NOP) {
                tmpBytes.storePositionAndSize(bytes, offset, entrySize);
                eventListener().onPut(VanillaChronicleMap.this, tmpBytes, metaDataBytes,
                        added, key, value, pos, this);
            }
        }

        void notifyGet(long offset, K key, V value) {
            if (eventListener() != MapEventListeners.NOP) {
                tmpBytes.storePositionAndSize(bytes, offset, entrySize);
                eventListener().onGetFound(VanillaChronicleMap.this, tmpBytes, metaDataBytes,
                        key, value);
            }
        }

        void notifyRemoved(long offset, K key, V value, final int pos) {
            if (eventListener() != MapEventListeners.NOP) {
                tmpBytes.storePositionAndSize(bytes, offset, entrySize);
                eventListener().onRemove(VanillaChronicleMap.this, tmpBytes, metaDataBytes,
                        key, value, pos, this);
            }
        }

        /**
         * Replaces value in existing entry. May cause entry relocation, because there may be not enough space
         * for new value in location already allocated for this entry.
         *
         * @param pos          index of the first block occupied by the entry
         * @param offset       relative offset of the entry in Segment bytes (before, i. e. including
         *                     metaData)
         * @param entry        relative pointer in Segment bytes
         * @param valueSizePos relative position of value size in entry
         * @param entryEndAddr absolute address of the entry end
         * @return relative offset of the entry in Segment bytes after putting value (that may cause entry
         * relocation)
         */
        long putValue(int pos, long offset, NativeBytes entry, long valueSizePos,
                      long entryEndAddr, ThreadLocalCopies copies, V value, Bytes valueBytes,
                      IntIntMultiMap searchedHashLookup) {
            long valueSizeAddr = entry.address() + valueSizePos;
            long newValueSize;
            VW valueWriter = null;
            MetaBytesWriter<V, VW> metaValueWriter = null;
            if (valueBytes != null) {
                newValueSize = valueBytes.remaining();
            } else {
                copies = valueWriterProvider.getCopies(copies);
                valueWriter = valueWriterProvider.get(copies, originalValueWriter);
                copies = metaValueWriterProvider.getCopies(copies);
                metaValueWriter = metaValueWriterProvider.get(
                        copies, originalMetaValueWriter, valueWriter, value);
                newValueSize = metaValueWriter.size(valueWriter, value);
            }
            long newValueAddr = alignment.alignAddr(
                    valueSizeAddr + valueSizeMarshaller.sizeEncodingSize(newValueSize));
            long newEntryEndAddr = newValueAddr + newValueSize;
            // Fast check before counting "sizes in blocks" that include
            // integral division
            newValueDoesNotFit:
            if (newEntryEndAddr != entryEndAddr) {
                long entryStartAddr = entryStartAddr(offset);
                long oldEntrySize = entryEndAddr - entryStartAddr;
                int oldSizeInBlocks = inBlocks(oldEntrySize);
                int newSizeInBlocks = inBlocks(newEntryEndAddr - entryStartAddr);
                if (newSizeInBlocks > oldSizeInBlocks) {
                    if (newSizeInBlocks > MAX_ENTRY_OVERSIZE_FACTOR) {
                        throw new IllegalArgumentException("Value too large: " +
                                "entry takes " + newSizeInBlocks + " blocks, " +
                                MAX_ENTRY_OVERSIZE_FACTOR + " is maximum.");
                    }
                    if (realloc(pos, oldSizeInBlocks, newSizeInBlocks))
                        break newValueDoesNotFit;
                    // RELOCATION
                    free(pos, oldSizeInBlocks);
                    eventListener().onRelocation(pos, this);
                    int prevPos = pos;
                    pos = alloc(newSizeInBlocks);
                    // putValue() is called from put() and replace()
                    // after successful search by key
                    replacePosInHashLookupOnRelocation(searchedHashLookup, prevPos, pos);
                    offset = offsetFromPos(pos);
                    // Moving metadata, key size and key.
                    // Don't want to fiddle with pseudo-buffers for this,
                    // since we already have all absolute addresses.
                    long newEntryStartAddr = entryStartAddr(offset);
                    NativeBytes.UNSAFE.copyMemory(entryStartAddr,
                            newEntryStartAddr, valueSizeAddr - entryStartAddr);
                    entry = entry(offset);
                    // END OF RELOCATION
                } else if (newSizeInBlocks < oldSizeInBlocks) {
                    // Freeing extra blocks
                    freeList.clear(pos + newSizeInBlocks, pos + oldSizeInBlocks);
                    // Do NOT reset nextPosToSearchFrom, because if value
                    // once was larger it could easily became oversized again,
                    // But if these blocks will be taken by that time,
                    // this entry will need to be relocated.
                }
            }
            // Common code for all cases
            entry.position(valueSizePos);
            valueSizeMarshaller.writeSize(entry, newValueSize);
            alignment.alignPositionAddr(entry);
            if (valueBytes != null) {
                entry.write(valueBytes);
            } else {
                metaValueWriter.write(valueWriter, entry, value);
            }
            return offset;
        }

        void replacePosInHashLookupOnRelocation(IntIntMultiMap searchedHashLookup, int prevPos, int pos) {
            searchedHashLookup.replacePrevPos(pos);
        }

        void clear() {
            lock();
            try {
                hashLookup.clear();
                freeList.clear();
                nextPosToSearchFrom = 0;
                resetSize();
            } finally {
                unlock();
            }
        }

        public Entry<K, V> getEntry(long pos) {
            bytes.position(offsetFromPos(pos) + metaDataBytes);

            long keySize = keySizeMarshaller.readSize(bytes);
            ThreadLocalCopies copies = keyReaderProvider.getCopies(null);
            K key = keyReaderProvider.get(copies, originalKeyReader).read(bytes, keySize);

            long valueSize = valueSizeMarshaller.readSize(bytes);
            alignment.alignPositionAddr(bytes);
            copies = valueReaderProvider.getCopies(copies);
            V value = valueReaderProvider.get(copies, originalValueReader).read(bytes, valueSize);

            return new WriteThroughEntry(key, value);
        }

        /**
         * Check there is no garbage in freeList.
         */
        void checkConsistency() {
            lock();
            try {
                IntIntMultiMap hashLookup = checkConsistencyHashLookup();
                for (int pos = 0; (pos = (int) freeList.nextSetBit(pos)) >= 0; ) {
                    PosPresentOnce check = new PosPresentOnce(pos);
                    hashLookup.forEach(check);
                    if (check.count != 1)
                        throw new AssertionError();
                    long offset = offsetFromPos(pos);
                    Bytes entry = entry(offset);
                    long keySize = keySizeMarshaller.readSize(entry);
                    entry.skip(keySize);
                    afterKeyHookOnCheckConsistency(entry);
                    long valueSize = valueSizeMarshaller.readSize(entry);
                    long sizeInBytes = entrySize(keySize, valueSize);
                    int entrySizeInBlocks = inBlocks(sizeInBytes);
                    if (!freeList.allSet(pos, pos + entrySizeInBlocks))
                        throw new AssertionError();
                    pos += entrySizeInBlocks;
                }
            } finally {
                unlock();
            }
        }

        void afterKeyHookOnCheckConsistency(Bytes entry) {
            // no-op
        }

        IntIntMultiMap checkConsistencyHashLookup() {
            return hashLookup;
        }

        private class PosPresentOnce implements IntIntMultiMap.EntryConsumer {
            int pos, count = 0;

            PosPresentOnce(int pos) {
                this.pos = pos;
            }

            @Override
            public void accept(int hash, int pos) {
                if (this.pos == pos) count++;
            }
        }
    }

    class EntryIterator implements Iterator<Entry<K, V>> {
        Entry<K, V> returnedEntry;
        private int returnedSeg = -1;
        private long returnedPos = -1L;
        private int nextSeg;
        private long nextPos;

        public EntryIterator() {
            advance(nextSeg = segments.length - 1, nextPos = -1L);
        }

        private boolean advance(int segIndex, long pos) {
            while (segIndex >= 0) {
                pos = segments[segIndex].getHashLookup().getPositions().nextSetBit(pos + 1L);
                if (pos >= 0L) {
                    nextSeg = segIndex;
                    nextPos = pos;
                    return true;
                } else {
                    segIndex--;
                    pos = -1L;
                }
            }
            nextSeg = -1;
            nextPos = -1L;
            return false;
        }

        @Override
        public boolean hasNext() {
            return nextSeg >= 0;
        }

        @Override
        public Entry<K, V> next() {
            for (; ; ) {
                int segIndex = nextSeg;
                long pos = nextPos;
                if (segIndex < 0)
                    throw new NoSuchElementException();
                final Segment segment = segments[segIndex];
                try {
                    segment.lock();
                    if (segment.getHashLookup().getPositions().isClear(pos)) {
                        // the pos was removed after the previous advance
                        advance(segIndex, pos);
                        continue;
                    }
                    advance(returnedSeg = segIndex, returnedPos = pos);
                    return returnedEntry = segment.getEntry(pos);
                } finally {
                    segment.unlock();
                }
            }
        }

        @Override
        public void remove() {
            int segIndex = returnedSeg;
            if (segIndex < 0)
                throw new IllegalStateException();
            final Segment segment = segments[segIndex];
            final int pos = (int) returnedPos;
            try {
                segment.lock();
                if (segment.getHashLookup().getPositions().isClear(pos)) {
                    // The case:
                    // 1. iterator.next() - thread 1
                    // 2. map.put() which cause relocation of the key, returned in above - thread 2
                    // OR map.remove() which remove this key - thread 2
                    // 3. iterator.remove() - thread 1
                    VanillaChronicleMap.this.remove(returnedEntry.getKey());
                } else {
                    removePresent(segment, pos);
                }
                returnedSeg = -1;
                returnedEntry = null;
            } finally {
                segment.unlock();
            }
        }

        void removePresent(Segment segment, int pos) {
            // TODO handle the case:
            // iterator.next() -- thread 1
            // map.put() which cause relocation of the key, returned above -- thread 2
            // map.put() which place a new key on the `pos` in current segment -- thread 3
            // iterator.remove() -- thread 1
            // The simple solution is to compare bytes in the map with the serialized bytes
            // of returnedEntry.getKey(), but it seems rather wasteful to workaround so rare
            // case.
            final long offset = segment.offsetFromPos(pos);
            final NativeBytes entry = segment.entry(offset);

            final long limit = entry.limit();
            final long keySize = keySizeMarshaller.readSize(entry);
            long position = entry.position();
            final int segmentHash = segmentHash(Hasher.hash(entry, position, position + keySize));

            entry.skip(keySize);
            long valueSize = readValueSize(entry);
            final long entryEndAddr = entry.positionAddr() + valueSize;
            segment.getHashLookup().remove(segmentHash, pos);
            segment.decrementSize();
            segment.free(pos, segment.inBlocks(entryEndAddr - segment.entryStartAddr(offset)));
            segment.notifyRemoved(offset, returnedEntry.getKey(), returnedEntry.getValue(), pos);
        }
    }

    class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        @NotNull
        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            try {
                V v = VanillaChronicleMap.this.get(e.getKey());
                return v != null && v.equals(e.getValue());
            } catch (ClassCastException ex) {
                return false;
            } catch (NullPointerException ex) {
                return false;
            }
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            try {
                Object key = e.getKey();
                Object value = e.getValue();
                return VanillaChronicleMap.this.remove(key, value);
            } catch (ClassCastException ex) {
                return false;
            } catch (NullPointerException ex) {
                return false;
            }
        }

        public int size() {
            return VanillaChronicleMap.this.size();
        }

        public boolean isEmpty() {
            return VanillaChronicleMap.this.isEmpty();
        }

        public void clear() {
            VanillaChronicleMap.this.clear();
        }
    }

    final class WriteThroughEntry extends SimpleEntry<K, V> {
        private static final long serialVersionUID = 0L;

        WriteThroughEntry(K key, V value) {
            super(key, value);
        }

        @Override
        public V setValue(V value) {
            put(getKey(), value);
            return super.setValue(value);
        }
    }
}
