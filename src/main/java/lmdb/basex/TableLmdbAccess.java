package lmdb.basex;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.basex.data.MetaData;
import org.basex.io.IO;
import org.basex.io.IOContent;
import org.basex.io.in.DataInput;
import org.basex.io.out.DataOutput;
import org.basex.io.random.Buffer;
import org.basex.io.random.Buffers;
import org.basex.io.random.TableAccess;
import org.basex.util.Array;
import org.basex.util.BitArray;
import org.basex.util.Util;
import org.fusesource.lmdbjni.Database;
import org.fusesource.lmdbjni.Transaction;

import java.io.IOException;
import java.util.Arrays;

import static lmdb.util.Byte.lmdbkey;

public class TableLmdbAccess extends TableAccess {

    protected Transaction tx;
    protected Database db;
    protected byte[] docid;

    private final Buffers bm = new Buffers();
    private BitArray usedPages;

    private int[] fpres;
    private int[] pages;
    private int size;
    private int used;

    private int page = -1;
    private int firstPre = -1;
    private int nextPre = -1;

    public TableLmdbAccess(final MetaData md, final Transaction tx, Database db, byte[] docid) throws IOException {
        super(md);

        this.tx = tx;
        this.db = db;
        this.docid = docid;

        // read meta and index data
        try(final DataInput in = new DataInput(new IOContent(db.get(tx,getStructKey())))) {
            final int s = in.readNum();
            size = s;

            // check if page index is regular and can be calculated (0: no pages)
            final int u = in.readNum();
            final boolean regular = u == 0 || u == Integer.MAX_VALUE;
            if(regular) {
                used = u == 0 ? 0 : s;
            } else {
                // read page index and first pre values from disk
                used = u;
                fpres = in.readNums();
                pages = in.readNums();
            }

            // read block bitmap
            if(!regular) {
                final int psize = in.readNum();
                usedPages = new BitArray(in.readLongs(psize), used);
            }
        }
    }

    @Override
    public synchronized void flush(final boolean all) throws IOException {
        if(tx.isReadOnly()) return;
        for(final Buffer b : bm.all()) if(b.dirty) write(b);
        if(!dirty || !all) return;
        try(ByteArrayOutputStream bos = new ByteArrayOutputStream(1024*32); final DataOutput out = new DataOutput(bos)) {
            final int sz = size;
            out.writeNum(sz);
            out.writeNum(used);
            // due to legacy issues, number of pages is written several times
            out.writeNum(sz);
            for(int s = 0; s < sz; s++) out.writeNum(fpres[s]);
            out.writeNum(sz);
            for(int s = 0; s < sz; s++) out.writeNum(pages[s]);
            out.writeLongs(usedPages.toArray());
            db.put(tx, getStructKey(), bos.toByteArray());
        }
        dirty = false;
    }

    @Override
    public synchronized void close() throws IOException {
        flush(true);
    }

    @Override
    public boolean lock(final boolean write) {
        return true;
    }

    @Override
    public synchronized int read1(final int pre, final int off) {
        final int o = off + cursor(pre);
        final byte[] b = bm.current().data;
        return b[o] & 0xFF;
    }

    @Override
    public synchronized int read2(final int pre, final int off) {
        final int o = off + cursor(pre);
        final byte[] b = bm.current().data;
        return ((b[o] & 0xFF) << 8) + (b[o + 1] & 0xFF);
    }

    @Override
    public synchronized int read4(final int pre, final int off) {
        final int o = off + cursor(pre);
        final byte[] b = bm.current().data;
        return ((b[o] & 0xFF) << 24) + ((b[o + 1] & 0xFF) << 16) +
                ((b[o + 2] & 0xFF) << 8) + (b[o + 3] & 0xFF);
    }

    @Override
    public synchronized long read5(final int pre, final int off) {
        final int o = off + cursor(pre);
        final byte[] b = bm.current().data;
        return ((long) (b[o] & 0xFF) << 32) + ((long) (b[o + 1] & 0xFF) << 24) +
                ((b[o + 2] & 0xFF) << 16) + ((b[o + 3] & 0xFF) << 8) + (b[o + 4] & 0xFF);
    }

    @Override
    public void write1(final int pre, final int off, final int v) {
        final int o = off + cursor(pre);
        final Buffer bf = bm.current();
        final byte[] b = bf.data;
        b[o] = (byte) v;
        bf.dirty = true;
    }

    @Override
    public void write2(final int pre, final int off, final int v) {
        final int o = off + cursor(pre);
        final Buffer bf = bm.current();
        final byte[] b = bf.data;
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
        bf.dirty = true;
    }

    @Override
    public void write4(final int pre, final int off, final int v) {
        final int o = off + cursor(pre);
        final Buffer bf = bm.current();
        final byte[] b = bf.data;
        b[o]     = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
        bf.dirty = true;
    }

    @Override
    public void write5(final int pre, final int off, final long v) {
        final int o = off + cursor(pre);
        final Buffer bf = bm.current();
        final byte[] b = bf.data;
        b[o]     = (byte) (v >>> 32);
        b[o + 1] = (byte) (v >>> 24);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 8);
        b[o + 4] = (byte) v;
        bf.dirty = true;
    }

    @Override
    protected void copy(final byte[] entries, final int pre, final int last) {
        for(int o = 0, i = pre; i < last; ++i, o += IO.NODESIZE) {
            final int off = cursor(i);
            final Buffer bf = bm.current();
            System.arraycopy(entries, o, bf.data, off, IO.NODESIZE);
            bf.dirty = true;
        }
    }

    @Override
    public void delete(final int pre, final int nr) {
        if(nr == 0) return;

        // get first page
        dirty();
        cursor(pre);

        // some useful variables to make code more readable
        int from = pre - firstPre;
        final int last = pre + nr;

        // check if all entries are in current page: handle and return
        if(last - 1 < nextPre) {
            final Buffer bf = bm.current();
            copy(bf.data, from + nr, bf.data, from, nextPre - last);
            updatePre(nr);

            // if whole page was deleted, remove it from the index
            if(nextPre == firstPre) {
                // mark the page as empty
                usedPages.clear(pages[page]);

                Array.move(fpres, page + 1, -1, used - page - 1);
                Array.move(pages, page + 1, -1, used - page - 1);

                --used;
                readPage(page);
            }
            return;
        }

        // handle pages whose entries are to be deleted entirely

        // first count them
        int unused = 0;
        while(nextPre < last) {
            if(from == 0) {
                ++unused;
                // mark the pages as empty; range clear cannot be used because the
                // pages may not be consecutive
                usedPages.clear(pages[page]);
            }
            setPage(page + 1);
            from = 0;
        }

        // if the last page is empty, clear the corresponding bit
        read(pages[page]);
        final Buffer bf = bm.current();
        if(nextPre == last) {
            usedPages.clear((int) bf.pos);
            ++unused;
            if(page < used - 1) readPage(page + 1);
            else ++page;
        } else {
            // delete entries at beginning of current (last) page
            copy(bf.data, last - firstPre, bf.data, 0, nextPre - last);
        }

        // now remove them from the index
        if(unused > 0) {
            Array.move(fpres, page, -unused, used - page);
            Array.move(pages, page, -unused, used - page);
            used -= unused;
            page -= unused;
        }

        // update index entry for this page
        fpres[page] = pre;
        firstPre = pre;
        updatePre(nr);
    }

    @Override
    public void insert(final int pre, final byte[] entries) {
        final int nnew = entries.length;
        if(nnew == 0) return;
        dirty();

        // number of records to be inserted
        final int nr = nnew >>> IO.NODEPOWER;

        int split = 0;
        if(used == 0) {
            // special case: insert new data into first page if database is empty
            readPage(0);
            usedPages.set(0);
            ++used;
        } else if(pre > 0) {
            // find the offset within the page where the new records will be inserted
            split = cursor(pre - 1) + IO.NODESIZE;
        }

        // number of bytes occupied by old records in the current page
        final int nold = nextPre - firstPre << IO.NODEPOWER;
        // number of bytes occupied by old records which will be moved at the end
        final int moved = nold - split;

        // special case: all entries fit in the current page
        Buffer bf = bm.current();
        if(nold + nnew <= IO.BLOCKSIZE) {
            Array.move(bf.data, split, nnew, moved);
            System.arraycopy(entries, 0, bf.data, split, nnew);
            bf.dirty = true;

            // increment first pre-values of pages after the last modified page
            for(int i = page + 1; i < used; ++i) fpres[i] += nr;
            // update cached variables (fpre is not changed)
            nextPre += nr;
            meta.size += nr;
            return;
        }

        // append old entries at the end of the new entries
        final byte[] all = new byte[nnew + moved];
        System.arraycopy(entries, 0, all, 0, nnew);
        System.arraycopy(bf.data, split, all, nnew, moved);

        // fill in the current page with new entries
        // number of bytes which fit in the first page
        int nrem = IO.BLOCKSIZE - split;
        if(nrem > 0) {
            System.arraycopy(all, 0, bf.data, split, nrem);
            bf.dirty = true;
        }

        // number of new required pages and remaining bytes
        final int req = all.length - nrem;
        int needed = req / IO.BLOCKSIZE;
        final int remain = req % IO.BLOCKSIZE;

        if(remain > 0) {
            // check if the last entries can fit in the page after the current one
            if(page + 1 < used) {
                final int o = occSpace(page + 1) << IO.NODEPOWER;
                if(remain <= IO.BLOCKSIZE - o) {
                    // copy the last records
                    readPage(page + 1);
                    bf = bm.current();
                    System.arraycopy(bf.data, 0, bf.data, remain, o);
                    System.arraycopy(all, all.length - remain, bf.data, 0, remain);
                    bf.dirty = true;
                    // reduce the pre value, since it will be later incremented with nr
                    fpres[page] -= remain >>> IO.NODEPOWER;
                    // go back to the previous page
                    readPage(page - 1);
                } else {
                    // there is not enough space in the page - allocate a new one
                    ++needed;
                }
            } else {
                // this is the last page - allocate a new one
                ++needed;
            }
        }

        // number of expected pages: existing pages + needed page - empty pages
        final int exp = size + needed - (size - used);
        if(exp > fpres.length) {
            // resize directory arrays if existing ones are too small
            final int ns = Math.max(fpres.length << 1, exp);
            fpres = Arrays.copyOf(fpres, ns);
            pages = Arrays.copyOf(pages, ns);
        }

        // make place for the pages where the new entries will be written
        Array.move(fpres, page + 1, needed, used - page - 1);
        Array.move(pages, page + 1, needed, used - page - 1);

        // write the all remaining entries
        while(needed-- > 0) {
            freePage();
            nrem += write(all, nrem);
            fpres[page] = fpres[page - 1] + IO.ENTRIES;
            pages[page] = (int) bm.current().pos;
        }

        // increment all fpre values after the last modified page
        for(int i = page + 1; i < used; ++i) fpres[i] += nr;

        meta.size += nr;

        // update cached variables
        firstPre = fpres[page];
        nextPre = page + 1 < used && fpres[page + 1] < meta.size ? fpres[page + 1] : meta.size;
    }

    @Override
    protected void dirty() {
        // initialize data structures required for performing updates
        if(fpres == null) {
            final int b = size;
            fpres = new int[b];
            pages = new int[b];
            for(int i = 0; i < b; i++) {
                fpres[i] = i * IO.ENTRIES;
                pages[i] = i;
            }
            usedPages = new BitArray(used, true);
        }
        dirty = true;
    }


    void setTx(Transaction tx) {
        this.tx = tx;
    }

    // PRIVATE METHODS ==========================================================

    /**
     * Searches for the page containing the entry for the specified pre value.
     * Reads the page and returns its offset inside the page.
     * @param pre pre of the entry to search for
     * @return offset of the entry in the page
     */
    private int cursor(final int pre) {
        int fp = firstPre, np = nextPre;
        if(pre < fp || pre >= np) {
            final int last = used - 1;
            int l = 0, h = last, m = page;
            while(l <= h) {
                if(pre < fp) h = m - 1;
                else if(pre >= np) l = m + 1;
                else break;
                m = h + l >>> 1;
                fp = fpre(m);
                np = m == last ? meta.size : fpre(m + 1);
            }
            if(l > h) throw Util.notExpected(
                    "Data Access out of bounds:" +
                            "\n- pre value: " + pre +
                            "\n- table size: " + meta.size +
                            "\n- first/next pre value: " + fp + '/' + np +
                            "\n- #total/used pages: " + size + '/' + used +
                            "\n- accessed page: " + m + " (" + l + " > " + h + ']');
            readPage(m);
        }
        return pre - firstPre << IO.NODEPOWER;
    }

    /**
     * Updates the page pointers.
     * @param p page index
     */
    private void setPage(final int p) {
        page = p;
        firstPre = fpre(p);
        nextPre = p + 1 >= used ? meta.size : fpre(p + 1);
    }

    /**
     * Updates the index pointers and fetches the requested page.
     * @param p page index
     */
    private void readPage(final int p) {
        setPage(p);
        read(page(p));
    }

    /**
     * Return the specified page index.
     * @param p index of the page to fetch
     * @return pre value
     */
    private int page(final int p) {
        return pages == null ? p : pages[p];
    }

    /**
     * Return the specified pre value.
     * @param p index of the page to fetch
     * @return pre value
     */
    private int fpre(final int p) {
        return fpres == null ? p * IO.ENTRIES : fpres[p];
    }

    /**
     * Reads a page from disk.
     * @param p page to fetch
     */
    private void read(final int p) {
        if(!bm.cursor(p)) return;

        final Buffer bf = bm.current();
        if(bf.dirty) try {
            write(bf);
        } catch (IOException e) {
            throw Util.notExpected(e);
        }
        bf.pos = p;
        if(p >= size) {
            size = p + 1;
        } else {
            bf.data = db.get(tx, lmdbkey(docid, (int)bf.pos));
        }
    }

    /**
     * Moves the cursor to a free page (either new or existing empty one).
     */
    private void freePage() {
        final int p = usedPages.nextFree(0);
        usedPages.set(p);
        read(p);
        ++used;
        ++page;
    }

    /**
     * Writes the specified buffer disk and resets the dirty flag.
     * @param bf buffer to write
     * @throws IOException I/O exception
     */
    private void write(final Buffer bf) throws IOException {
        db.put(tx, lmdbkey(docid, (int)bf.pos), bf.data);
        bf.dirty = false;
    }



    /**
     * Updates the firstPre index entries.
     * @param nr number of entries to move
     */
    private void updatePre(final int nr) {
        // update index entries for all following pages and reduce counter
        for(int i = page + 1; i < used; ++i) fpres[i] -= nr;
        meta.size -= nr;
        nextPre = page + 1 < used && fpres[page + 1] < meta.size ? fpres[page + 1] : meta.size;
    }

    /**
     * Convenience method for copying pages.
     * @param s source array
     * @param sp source position
     * @param d destination array
     * @param dp destination position
     * @param l source length
     */
    private void copy(final byte[] s, final int sp, final byte[] d, final int dp, final int l) {
        System.arraycopy(s, sp << IO.NODEPOWER, d, dp << IO.NODEPOWER, l << IO.NODEPOWER);
        bm.current().dirty = true;
    }

    /**
     * Fill the current buffer with bytes from the specified array from the
     * specified offset.
     * @param s source array
     * @param o offset from the beginning of the array
     * @return number of written bytes
     */
    private int write(final byte[] s, final int o) {
        final Buffer bf = bm.current();
        final int len = Math.min(IO.BLOCKSIZE, s.length - o);
        System.arraycopy(s, o, bf.data, 0, len);
        bf.dirty = true;
        return len;
    }

    /**
     * Calculate the occupied space in a page.
     * @param i page index
     * @return occupied space in number of records
     */
    private int occSpace(final int i) {
        return (i + 1 < used ? fpres[i + 1] : meta.size) - fpres[i];
    }

    private byte[] getStructKey() {
        return getStructKey(docid);
    }
    // using last possible key in table access db for fpres and pages
    static byte[] getStructKey(byte[] did) {
        return new byte[] {
                did[0],
                did[1],
                did[2],
                did[3],
                (byte)(0xff),
                (byte)(0xff),
                (byte)(0xff),
                (byte)(0xff)
        };
    }
}
