/*
 * Copyright 2013-2014 by Daniel Lemire, Owen Kaser and Samy Chambi
 * Licensed under the Apache License, Version 2.0.
 */
package org.roaringbitmap.buffer;

import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Serializable;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Iterator;

import org.roaringbitmap.ShortIterator;

/**
 * Simple bitset-like container. Unlike org.roaringbitmap.BitmapContainer, this
 * class uses a LongBuffer to store data.
 * 
 */
public final class BitmapContainer extends Container implements Cloneable,
        Serializable {
        LongBuffer bitmap;

        int cardinality;

        private static final long serialVersionUID = 2L;

        private static boolean USEINPLACE = true; // optimization flag

        protected static int maxcapacity = 1 << 16;

        /**
         * Create a bitmap container with all bits set to false
         */
        public BitmapContainer() {
                this.cardinality = 0;
                this.bitmap = LongBuffer.allocate(maxcapacity / 64);
        }

        /**
         * Create a bitmap container with a run of ones from firstOfRun to
         * lastOfRun, inclusive caller must ensure that the range isn't so small
         * that an ArrayContainer should have been created instead
         * 
         * @param firstOfRun
         *                first index
         * @param lastOfRun
         *                last index (range is inclusive)
         */
        public BitmapContainer(final int firstOfRun, final int lastOfRun) {
                this.cardinality = lastOfRun - firstOfRun + 1;
                this.bitmap = LongBuffer.allocate(maxcapacity / 64);
                if (this.cardinality == maxcapacity) // perhaps a common case
                        for (int k = 0; k < bitmap.limit(); ++k)
                                bitmap.put(k, -1L);
                else {
                        final int firstWord = firstOfRun / 64;
                        final int lastWord = lastOfRun / 64;
                        final int zeroPrefixLength = firstOfRun & 63;
                        final int zeroSuffixLength = 63 - (lastOfRun & 63);
                        for (int k = firstWord; k < lastWord + 1; ++k)
                                bitmap.put(k, -1L);
                        bitmap.put(firstWord, bitmap.get(firstWord)
                                ^ ((1L << zeroPrefixLength) - 1));
                        final long blockOfOnes = (1L << zeroSuffixLength) - 1;
                        final long maskOnLeft = blockOfOnes << (64 - zeroSuffixLength);
                        bitmap.put(lastWord, bitmap.get(lastWord) ^ maskOnLeft);
                }
        }

        private BitmapContainer(int newcardinality, LongBuffer newbitmap) {
                this.cardinality = newcardinality;
                this.bitmap = LongBuffer.allocate(newbitmap.limit());
                newbitmap.rewind();
                this.bitmap.put(newbitmap);
        }

        /**
         * Construct a new BitmapContainer backed by the provided LongBuffer.
         * 
         * @param array
         *                LongBuffer where the data is stored
         * @param cardinality
         *                cardinality (number of values stored)
         */
        public BitmapContainer(final LongBuffer array, final int cardinality) {
                if (array.limit() != maxcapacity / 64)
                        throw new RuntimeException(
                                "Mismatch between buffer and storage requirements: "
                                        + array.limit() + " vs. " + maxcapacity
                                        / 64);
                this.cardinality = cardinality;
                this.bitmap = array;
        }

        @Override
        public Container add(final short i) {
                final int x = Util.toIntUnsigned(i);
                final long previous = bitmap.get(x / 64);
                bitmap.put(x / 64, previous | (1l << x));
                cardinality += (previous ^ bitmap.get(x / 64)) >>> x;
                return this;
        }

        @Override
        public ArrayContainer and(final ArrayContainer value2) {
                final ArrayContainer answer = new ArrayContainer(
                        value2.content.limit());
                for (int k = 0; k < value2.getCardinality(); ++k)
                        if (this.contains(value2.content.get(k)))
                                answer.content.put(answer.cardinality++,
                                        value2.content.get(k));
                return answer;
        }

        @Override
        public Container and(final BitmapContainer value2) {
                int newcardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k) {
                        newcardinality += Long.bitCount(this.bitmap.get(k)
                                & value2.bitmap.get(k));
                }
                if (newcardinality > ArrayContainer.DEFAULTMAXSIZE) {
                        final BitmapContainer answer = new BitmapContainer();
                        for (int k = 0; k < answer.bitmap.limit(); ++k) {
                                answer.bitmap.put(k, this.bitmap.get(k)
                                        & value2.bitmap.get(k));
                        }
                        answer.cardinality = newcardinality;
                        return answer;
                }
                final ArrayContainer ac = new ArrayContainer(newcardinality);
                Util.fillArrayAND(ac.content, this.bitmap, value2.bitmap);
                ac.cardinality = newcardinality;
                return ac;
        }

        @Override
        public Container andNot(final ArrayContainer value2) {
                final BitmapContainer answer = clone();
                for (int k = 0; k < value2.cardinality; ++k) {
                        final int i = Util.toIntUnsigned(value2.content.get(k)) >>> 6;
                        answer.bitmap.put(i, answer.bitmap.get(i)
                                & (~(1l << value2.content.get(k))));
                        answer.cardinality -= (answer.bitmap.get(i) ^ this.bitmap
                                .get(i)) >>> value2.content.get(k);
                }
                if (answer.cardinality <= ArrayContainer.DEFAULTMAXSIZE)
                        return answer.toArrayContainer();
                return answer;
        }

        @Override
        public Container andNot(final BitmapContainer value2) {
                int newcardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k) {
                        newcardinality += Long.bitCount(this.bitmap.get(k)
                                & (~value2.bitmap.get(k)));
                }
                if (newcardinality > ArrayContainer.DEFAULTMAXSIZE) {
                        final BitmapContainer answer = new BitmapContainer();
                        for (int k = 0; k < answer.bitmap.limit(); ++k) {
                                answer.bitmap.put(k, this.bitmap.get(k)
                                        & (~value2.bitmap.get(k)));
                        }
                        answer.cardinality = newcardinality;
                        return answer;
                }
                final ArrayContainer ac = new ArrayContainer(newcardinality);
                Util.fillArrayANDNOT(ac.content, this.bitmap, value2.bitmap);
                ac.cardinality = newcardinality;
                return ac;
        }

        @Override
        public void clear() {
                if (cardinality != 0) {
                        cardinality = 0;
                        for (int k = 0; k < bitmap.limit(); ++k)
                                bitmap.put(k, 0);
                }
        }

        @Override
        public BitmapContainer clone() {
                return new BitmapContainer(this.cardinality, this.bitmap);
        }

        @Override
        public boolean contains(final short i) {
                final int x = Util.toIntUnsigned(i);
                return (bitmap.get(x / 64) & (1l << x)) != 0;
        }

        @Override
        public boolean equals(Object o) {
                if (o instanceof BitmapContainer) {
                        final BitmapContainer srb = (BitmapContainer) o;
                        if (srb.cardinality != this.cardinality)
                                return false;
                        for (int k = 0; k < this.bitmap.limit(); ++k)
                                if (this.bitmap.get(k) != srb.bitmap.get(k))
                                        return false;
                        return true;
                }
                return false;
        }

        /**
         * Fill the array with set bits
         * 
         * @param array
         *                container (should be large enoug)
         */
        protected void fillArray(final short[] array) {
                int pos = 0;
                for (int k = 0; k < bitmap.limit(); ++k) {
                        long bitset = bitmap.get(k);
                        while (bitset != 0) {
                                final long t = bitset & -bitset;
                                array[pos++] = (short) (k * 64 + Long
                                        .bitCount(t - 1));
                                bitset ^= t;
                        }
                }
        }

        /**
         * Fill the array with set bits
         * 
         * @param content
         *                container (should be large enoug)
         */
        protected void fillArray(final ShortBuffer content) {
                int pos = 0;
                for (int k = 0; k < bitmap.limit(); ++k) {
                        long bitset = bitmap.get(k);
                        while (bitset != 0) {
                                final long t = bitset & -bitset;
                                content.put(pos++,
                                        (short) (k * 64 + Long.bitCount(t - 1)));
                                bitset ^= t;
                        }
                }
        }

        @Override
        public void fillLeastSignificant16bits(int[] x, int i, int mask) {
                int pos = i;
                for (int k = 0; k < bitmap.limit(); ++k) {
                        long bitset = bitmap.get(k);
                        while (bitset != 0) {
                                final long t = bitset & -bitset;
                                x[pos++] = (k * 64 + Long.bitCount(t - 1))
                                        | mask;
                                bitset ^= t;
                        }
                }
        }

        @Override
		protected int getArraySizeInBytes() {
			return maxcapacity / 8;
		}

        @Override
        public int getCardinality() {
                return cardinality;
        }

        @Override
        public ShortIterator getShortIterator() {
                return new ShortIterator() {
                        int i = BitmapContainer.this.nextSetBit(0);

                        int j;

                        @Override
                        public boolean hasNext() {
                                return i >= 0;
                        }

                        @Override
                        public short next() {
                                j = i;
                                i = BitmapContainer.this.nextSetBit(i + 1);
                                return (short) j;
                        }

                        @Override
                        public void remove() {
                                BitmapContainer.this.remove((short) j);
                        }

                };

        }

        @Override
        public int getSizeInBytes() {
                return this.bitmap.limit() * 8;
        }

        @Override
        public int hashCode() {
                long hash = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k)
                        hash += 31 * this.bitmap.get(k);
                return (int) (hash >> 32);
        }

        @Override
        public Container iand(final ArrayContainer B2) {
                return B2.and(this);// no inplace possible
        }

        @Override
        public Container iand(final BitmapContainer B2) {
                int newcardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k) {
                        newcardinality += Long.bitCount(this.bitmap.get(k)
                                & B2.bitmap.get(k));
                }
                if (newcardinality > ArrayContainer.DEFAULTMAXSIZE) {
                        for (int k = 0; k < this.bitmap.limit(); ++k) {
                                this.bitmap.put(k, this.bitmap.get(k)
                                        & B2.bitmap.get(k));
                        }
                        this.cardinality = newcardinality;
                        return this;
                }
                final ArrayContainer ac = new ArrayContainer(newcardinality);
                Util.fillArrayAND(ac.content, this.bitmap, B2.bitmap);
                ac.cardinality = newcardinality;
                return ac;
        }

        @Override
        public Container iandNot(final ArrayContainer B2) {
                for (int k = 0; k < B2.cardinality; ++k) {
                        this.remove(B2.content.get(k));
                }
                if (cardinality <= ArrayContainer.DEFAULTMAXSIZE)
                        return this.toArrayContainer();
                return this;
        }

        @Override
        public Container iandNot(final BitmapContainer B2) {
                int newcardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k) {
                        newcardinality += Long.bitCount(this.bitmap.get(k)
                                & (~B2.bitmap.get(k)));
                }
                if (newcardinality > ArrayContainer.DEFAULTMAXSIZE) {
                        for (int k = 0; k < this.bitmap.limit(); ++k) {
                                this.bitmap.put(k, this.bitmap.get(k)
                                        & (~B2.bitmap.get(k)));
                        }
                        this.cardinality = newcardinality;
                        return this;
                }
                final ArrayContainer ac = new ArrayContainer(newcardinality);
                Util.fillArrayANDNOT(ac.content, this.bitmap, B2.bitmap);
                ac.cardinality = newcardinality;
                return ac;
        }

        // complicated so that it should be reasonably efficient even when the
        // ranges are small
        @Override
        public Container inot(final int firstOfRange, final int lastOfRange) {
                return not(this, firstOfRange, lastOfRange);
        }

        @Override
        public BitmapContainer ior(final ArrayContainer value2) {
                for (int k = 0; k < value2.cardinality; ++k) {
                        final int i = Util.toIntUnsigned(value2.content.get(k)) >>> 6;
                        this.cardinality += ((~this.bitmap.get(i)) & (1l << value2.content
                                .get(k))) >>> value2.content.get(k);
                        this.bitmap.put(i, this.bitmap.get(i)
                                | (1l << value2.content.get(k)));
                }
                return this;
        }

        @Override
        public Container ior(final BitmapContainer B2) {
                this.cardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); k++) {
                        this.bitmap.put(k,
                                this.bitmap.get(k) | B2.bitmap.get(k));
                        this.cardinality += Long.bitCount(this.bitmap.get(k));
                }
                return this;
        }

        @Override
        public Iterator<Short> iterator() {
                return new Iterator<Short>() {
                        int i = BitmapContainer.this.nextSetBit(0);

                        int j;

                        @Override
                        public boolean hasNext() {
                                return i >= 0;
                        }

                        @Override
                        public Short next() {
                                j = i;
                                i = BitmapContainer.this.nextSetBit(i + 1);
                                return new Short((short) j);
                        }

                        @Override
                        public void remove() {
                                BitmapContainer.this.remove((short) j);
                        }

                };
        }

        @Override
        public Container ixor(final ArrayContainer value2) {
                for (int k = 0; k < value2.getCardinality(); ++k) {
                        final int index = Util.toIntUnsigned(value2.content
                                .get(k)) >>> 6;
                        this.cardinality += 1 - 2 * ((this.bitmap.get(index) & (1l << value2.content
                                .get(k))) >>> value2.content.get(k));
                        this.bitmap.put(index, this.bitmap.get(index)
                                ^ (1l << value2.content.get(k)));
                }
                if (this.cardinality <= ArrayContainer.DEFAULTMAXSIZE) {
                        return this.toArrayContainer();
                }
                return this;
        }

        @Override
        public Container ixor(BitmapContainer B2) {
                int newcardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k) {
                        newcardinality += Long.bitCount(this.bitmap.get(k)
                                ^ B2.bitmap.get(k));
                }
                if (newcardinality > ArrayContainer.DEFAULTMAXSIZE) {
                        for (int k = 0; k < this.bitmap.limit(); ++k) {
                                this.bitmap.put(k, this.bitmap.get(k)
                                        ^ B2.bitmap.get(k));
                        }
                        this.cardinality = newcardinality;
                        return this;
                }
                final ArrayContainer ac = new ArrayContainer(newcardinality);
                Util.fillArrayXOR(ac.content, this.bitmap, B2.bitmap);
                ac.cardinality = newcardinality;
                return ac;
        }

        protected void loadData(final ArrayContainer arrayContainer) {
                this.cardinality = arrayContainer.cardinality;
                for (int k = 0; k < arrayContainer.cardinality; ++k) {
                        final short x = arrayContainer.content.get(k);
                        bitmap.put(Util.toIntUnsigned(x) / 64,
                                bitmap.get(Util.toIntUnsigned(x) / 64)
                                        | (1l << x));
                }
        }

        /**
         * Find the index of the next set bit greater or equal to i, returns -1
         * if none found.
         * 
         * @param i
         *                starting index
         * @return index of the next set bit
         */
        public int nextSetBit(final int i) {
                int x = i / 64;
                if (x >= bitmap.limit())
                        return -1;
                long w = bitmap.get(x);
                w >>>= i;
                if (w != 0) {
                        return i + Long.numberOfTrailingZeros(w);
                }
                ++x;
                for (; x < bitmap.limit(); ++x) {
                        if (bitmap.get(x) != 0) {
                                return x
                                        * 64
                                        + Long.numberOfTrailingZeros(bitmap
                                                .get(x));
                        }
                }
                return -1;
        }

        /**
         * Find the index of the next unset bit greater or equal to i, returns
         * -1 if none found.
         * 
         * @param i
         *                starting index
         * @return index of the next unset bit
         */
        public short nextUnsetBit(final int i) {
                int x = i / 64;
                long w = ~bitmap.get(x);
                w >>>= i;
                if (w != 0) {
                        return (short) (i + Long.numberOfTrailingZeros(w));
                }
                ++x;
                for (; x < bitmap.limit(); ++x) {
                        if (bitmap.get(x) != ~0L) {
                                return (short) (x * 64 + Long
                                        .numberOfTrailingZeros(~bitmap.get(x)));
                        }
                }
                return -1;
        }

        // answer could be a new BitmapContainer, or (for inplace) it can be
        // "this"
        private Container not(BitmapContainer answer, final int firstOfRange,
                final int lastOfRange) {
                assert bitmap.limit() == maxcapacity / 64; // checking
                                                           // assumption
                                                           // that partial
                                                           // bitmaps are not
                                                           // allowed
                // an easy case for full range, should be common
                if (lastOfRange - firstOfRange + 1 == maxcapacity) {
                        final int newCardinality = maxcapacity - cardinality;
                        for (int k = 0; k < this.bitmap.limit(); ++k)
                                answer.bitmap.put(k, ~this.bitmap.get(k));
                        answer.cardinality = newCardinality;
                        if (newCardinality <= ArrayContainer.DEFAULTMAXSIZE)
                                return answer.toArrayContainer();
                        return answer;
                }

                // could be optimized to first determine the answer cardinality,
                // rather than update/create bitmap and then possibly convert

                int cardinalityChange = 0;
                final int rangeFirstWord = firstOfRange / 64;
                final int rangeFirstBitPos = firstOfRange & 63;
                final int rangeLastWord = lastOfRange / 64;
                final long rangeLastBitPos = lastOfRange & 63;

                // if not in place, we need to duplicate stuff before
                // rangeFirstWord and after rangeLastWord
                if (answer != this) {
                        for (int i = 0; i < rangeFirstWord; ++i)
                                answer.bitmap.put(i, bitmap.get(i));
                        for (int i = rangeLastWord + 1; i < bitmap.limit(); ++i)
                                answer.bitmap.put(i, bitmap.get(i));
                }

                // unfortunately, the simple expression gives the wrong mask for
                // rangeLastBitPos==63
                // no branchless way comes to mind
                final long maskOnLeft = (rangeLastBitPos == 63) ? -1L
                        : (1L << (rangeLastBitPos + 1)) - 1;

                long mask = -1L; // now zero out stuff in the prefix
                mask ^= ((1L << rangeFirstBitPos) - 1);

                if (rangeFirstWord == rangeLastWord) {
                        // range starts and ends in same word (may have
                        // unchanged bits on both left and right)
                        mask &= maskOnLeft;
                        cardinalityChange = -Long.bitCount(bitmap
                                .get(rangeFirstWord));
                        answer.bitmap.put(rangeFirstWord,
                                bitmap.get(rangeFirstWord) ^ mask);
                        cardinalityChange += Long.bitCount(answer.bitmap
                                .get(rangeFirstWord));
                        answer.cardinality = cardinality + cardinalityChange;

                        if (answer.cardinality <= ArrayContainer.DEFAULTMAXSIZE)
                                return answer.toArrayContainer();
                        return answer;
                }

                // range spans words
                cardinalityChange += -Long.bitCount(bitmap.get(rangeFirstWord));
                answer.bitmap.put(rangeFirstWord, bitmap.get(rangeFirstWord)
                        ^ mask);
                cardinalityChange += Long.bitCount(answer.bitmap
                        .get(rangeFirstWord));

                cardinalityChange += -Long.bitCount(bitmap.get(rangeLastWord));
                answer.bitmap.put(rangeLastWord, bitmap.get(rangeLastWord)
                        ^ maskOnLeft);
                cardinalityChange += Long.bitCount(answer.bitmap
                        .get(rangeLastWord));

                // negate the words, if any, strictly between first and last
                for (int i = rangeFirstWord + 1; i < rangeLastWord; ++i) {
                        cardinalityChange += (64 - 2 * Long.bitCount(bitmap
                                .get(i)));
                        answer.bitmap.put(i, ~bitmap.get(i));
                }
                answer.cardinality = cardinality + cardinalityChange;

                if (answer.cardinality <= ArrayContainer.DEFAULTMAXSIZE)
                        return answer.toArrayContainer();
                return answer;
        }

        @Override
        public Container not(final int firstOfRange, final int lastOfRange) {
                return not(new BitmapContainer(), firstOfRange, lastOfRange);
        }

        @Override
        public BitmapContainer or(final ArrayContainer value2) {
                final BitmapContainer answer = clone();
                for (int k = 0; k < value2.cardinality; ++k) {
                        final int i = Util.toIntUnsigned(value2.content.get(k)) >>> 6;
                        answer.cardinality += ((~answer.bitmap.get(i)) & (1l << value2.content
                                .get(k))) >>> value2.content.get(k);
                        answer.bitmap.put(i, answer.bitmap.get(i)
                                | (1l << value2.content.get(k)));
                }
                return answer;
        }

        @Override
        public Container or(final BitmapContainer value2) {
                if (USEINPLACE) {
                        final BitmapContainer value1 = this.clone();
                        return value1.ior(value2);
                }
                final BitmapContainer answer = new BitmapContainer();
                answer.cardinality = 0;
                for (int k = 0; k < answer.bitmap.limit(); ++k) {
                        answer.bitmap.put(k,
                                this.bitmap.get(k) | value2.bitmap.get(k));
                        answer.cardinality += Long.bitCount(answer.bitmap
                                .get(k));
                }
                return answer;
        }

        @Override
        public void readExternal(ObjectInput in) throws IOException,
                ClassNotFoundException {
                final byte[] buffer = new byte[8];
                // little endian
                this.cardinality = 0;
                for (int k = 0; k < bitmap.limit(); ++k) {
                        in.readFully(buffer);
                        bitmap.put(
                                k,
                                (((long) buffer[7] << 56)
                                        + ((long) (buffer[6] & 255) << 48)
                                        + ((long) (buffer[5] & 255) << 40)
                                        + ((long) (buffer[4] & 255) << 32)
                                        + ((long) (buffer[3] & 255) << 24)
                                        + ((buffer[2] & 255) << 16)
                                        + ((buffer[1] & 255) << 8) + ((buffer[0] & 255) << 0)));
                        this.cardinality += Long.bitCount(bitmap.get(k));
                }
        }

        @Override
        public Container remove(final short i) {
                final int x = Util.toIntUnsigned(i);
                if (cardinality == ArrayContainer.DEFAULTMAXSIZE) {// this is
                                                                   // the
                                                                   // uncommon
                                                                   // path
                        if ((bitmap.get(x / 64) & (1l << x)) != 0) {
                                --cardinality;
                                bitmap.put(x / 64, bitmap.get(x / 64)
                                        & ~(1l << x));
                                return this.toArrayContainer();
                        }
                }
                cardinality -= (bitmap.get(x / 64) & (1l << x)) >>> x;
                bitmap.put(x / 64, bitmap.get(x / 64) & ~(1l << x));
                return this;
        }

        /**
         * Copies the data to an array container
         * 
         * @return the array container
         */
        public ArrayContainer toArrayContainer() {
                final ArrayContainer ac = new ArrayContainer(cardinality);
                ac.loadData(this);
                return ac;
        }

        @Override
        public String toString() {
                final StringBuffer sb = new StringBuffer();
                sb.append("{");
                int i = this.nextSetBit(0);
                while (i >= 0) {
                        sb.append(i);
                        i = this.nextSetBit(i + 1);
                        if (i >= 0)
                                sb.append(",");
                }
                sb.append("}");
                return sb.toString();
        }

        @Override
        public void trim() {
        }

        @Override
        protected void writeArray(DataOutput out) throws IOException {

                final byte[] buffer = new byte[8];
                // little endian
                for (int k = 0; k < maxcapacity / 64; ++k) {
                        final long w = bitmap.get(k);
                        buffer[0] = (byte) (w >>> 0);
                        buffer[1] = (byte) (w >>> 8);
                        buffer[2] = (byte) (w >>> 16);
                        buffer[3] = (byte) (w >>> 24);
                        buffer[4] = (byte) (w >>> 32);
                        buffer[5] = (byte) (w >>> 40);
                        buffer[6] = (byte) (w >>> 48);
                        buffer[7] = (byte) (w >>> 56);
                        out.write(buffer, 0, 8);
                }
        }

        @Override
        public void writeExternal(ObjectOutput out) throws IOException {
                writeArray(out);
        }

        @Override
        public Container xor(final ArrayContainer value2) {
                final BitmapContainer answer = clone();
                for (int k = 0; k < value2.getCardinality(); ++k) {
                        final int index = Util.toIntUnsigned(value2.content
                                .get(k)) >>> 6;
                        answer.cardinality += 1 - 2 * ((answer.bitmap
                                .get(index) & (1l << value2.content.get(k))) >>> value2.content
                                .get(k));
                        answer.bitmap.put(index, answer.bitmap.get(index)
                                ^ (1l << value2.content.get(k)));
                }
                if (answer.cardinality <= ArrayContainer.DEFAULTMAXSIZE)
                        return answer.toArrayContainer();
                return answer;
        }

		@Override
        public Container xor(BitmapContainer value2) {
                int newcardinality = 0;
                for (int k = 0; k < this.bitmap.limit(); ++k) {
                        newcardinality += Long.bitCount(this.bitmap.get(k)
                                ^ value2.bitmap.get(k));
                }
                if (newcardinality > ArrayContainer.DEFAULTMAXSIZE) {
                        final BitmapContainer answer = new BitmapContainer();
                        for (int k = 0; k < answer.bitmap.limit(); ++k) {
                                answer.bitmap.put(k, this.bitmap.get(k)
                                        ^ value2.bitmap.get(k));
                        }
                        answer.cardinality = newcardinality;
                        return answer;
                }
                final ArrayContainer ac = new ArrayContainer(newcardinality);
                Util.fillArrayXOR(ac.content, this.bitmap, value2.bitmap);
                ac.cardinality = newcardinality;
                return ac;
        }

}