package lmdb.util;

import lmdb.basex.LmdbQueryContext;
import org.basex.build.json.JsonOptions;
import org.basex.build.json.JsonSerialOptions;
import org.basex.io.IOContent;
import org.basex.io.serial.SerialMethod;
import org.basex.io.serial.Serializer;
import org.basex.io.serial.SerializerOptions;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.iter.Iter;
import org.basex.query.value.item.Item;
import org.basex.query.value.node.DBNode;
import org.basex.query.value.type.NodeType;
import org.basex.query.value.type.SeqType;
import org.basex.util.options.Options;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

@SuppressWarnings("unchecked")
public class XQuery {   // TODO: retire

    protected XQuery() {
    }

    public static LmdbQueryContext getContext(String query, String context, Map<String,Object> var) throws QueryException {
        QueryContext ctx = new QueryContext();
        try {
            ctx.parse(query);
            if(context != null) ctx.context(new DBNode(new IOContent(context)));
            if(var != null && var.size() > 0) for(String k: var.keySet()) ctx.bind(k, var.get(k));
            ctx.compile();
            return null;
        } catch(IOException ioe) {
            try { ctx.close(); } catch (IOException i) {}
            throw new QueryException(ioe);
        }
    }

    public static LmdbQueryContext getContext(String query, Map<String,Object> var) throws QueryException {
        return getContext(query, null, var);
    }

    public static LmdbQueryContext getContext(String query) throws QueryException {
        return getContext(query, null, null);
    }

    public static void query(String query, String context, OutputStream result, Map<String,Object> var, String method) throws QueryException {
        try (LmdbQueryContext ctx = getContext(query, context, var)) {
            query(ctx, result, method, null);
        } catch(IOException ioe) {
            throw new QueryException(ioe);
        }
    }

    public static void query(LmdbQueryContext ctx, OutputStream result, String method, String indent) throws QueryException {
        query(ctx, result, method, Boolean.parseBoolean(indent));
    }

    public static void query(LmdbQueryContext ctx, OutputStream result, String method, boolean indent) throws QueryException {
        try(Serializer s = Serializer.get(result, getSerializerOptions(method, indent))) {
            Iter iter = ctx.iter();
            Item i = null;
            while ((i = iter.next()) != null) {
                if (i.type == NodeType.ATT || i.type == NodeType.NSP || i.type.instanceOf(SeqType.ANY_ARRAY)) {
                    result.flush();
                    result.write(i.toString().getBytes());
                } else {
                    s.serialize(i);
                }
            }
        } catch(IOException ioe) {
            throw new QueryException(ioe);
        }
    }

    public static void query(LmdbQueryContext ctx, OutputStream result) throws QueryException {
        query(ctx, result, null, null);
    }

    public static void query(String query, OutputStream result) throws QueryException {
        query(query, null, result, null, null);
    }

    public static void query(String query, String context, OutputStream result) throws QueryException {
        query(query, context, result, null, null);
    }

    public static void query(String query, String context, OutputStream result, Map<String,Object> var) throws QueryException {
        query(query, context, result, var, null);
    }

    public static void query(String query, OutputStream result, Map<String,Object> var) throws QueryException {
        query(query, null, result, var, null);
    }

    public static String getString(String query, String context, Map<String, Object> var, String method) throws QueryException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        query(query, context, result, var, method);
        return result.toString();
    }

    public static String getString(String query) throws QueryException {
        return getString(query, null, (Map) null, null);
    }

    public static String getString(String query, String context) throws QueryException {
        return getString(query, context, (Map) null, null);
    }

    public static String getString(String query, String context, Map<String,Object> var) throws QueryException {
        return getString(query, context, var, null);
    }

    public static InputStream getStream(final LmdbQueryContext ctx, final String method) throws QueryException {
        try {
            return new InputStream() {
                private ByteArrayOutputStream result = new ByteArrayOutputStream();
                private Serializer s = Serializer.get(result, getSerializerOptions(method));
                private Iter iter = ctx.iter();
                private Item i = null;
                private byte[] b = null;
                private int off = -1;
                public void close() throws IOException {
                    s.close();
                    ctx.close();
                }
                public int read() throws IOException {
                    if((b == null || off >= b.length) && !next()) return -1;
                    return (int)b[off++];
                }
                private boolean next() throws IOException {
                    try {
                        if((i = iter.next()) == null) return false;
                        result.reset();
                        if(i.type == NodeType.ATT || i.type == NodeType.NSP || i.type.instanceOf(SeqType.ANY_ARRAY)) {
                            result.flush();
                            result.write(i.toString().getBytes());
                        } else {
                            s.serialize(i);
                        }
                        b = result.toByteArray();
                        off = 0;
                        return true;
                    } catch(QueryException qe) {
                        throw new IOException(qe);
                    }
                }
            };
        } catch(IOException ioe) {
            throw new QueryException(ioe);
        } finally {
            try { ctx.close(); } catch (IOException e) {}
        }
    }

    public static InputStream getStream(final String query, final String context, final Map<String,Object> var) throws QueryException {
        try (LmdbQueryContext ctx = getContext(query, context, var)) {
            return getStream(ctx, null);
        } catch(IOException ioe) {
            throw new QueryException(ioe);
        }
    }

    public static InputStream getStream(final String query, final Map<String,Object> var) throws QueryException {
        return getStream(getContext(query, null, var), null);
    }

    public static InputStream getStream(final String query, final String context) throws QueryException {
        return getStream(getContext(query, context, null), null);
    }

    public static InputStream getStream(final String query) throws QueryException {
        return getStream(getContext(query, null, null), null);
    }

    public static InputStream getStream(final LmdbQueryContext ctx) throws QueryException {
        return getStream(ctx, null);
    }

    private static SerializerOptions getSerializerOptions(String method, boolean indent, String jsonFormat) {
        method = method == null ? "text/xml" : method.toLowerCase();
        SerializerOptions opt = new SerializerOptions();
        opt.set(SerializerOptions.METHOD, SerialMethod.XML);
        if(method != null && !method.contains("/xml")) {
            if (method.contains("plain")) opt.set(SerializerOptions.METHOD, SerialMethod.TEXT);
            else if (method.contains("xhtml")) opt.set(SerializerOptions.METHOD, SerialMethod.XHTML);
            else if (method.contains("html")) opt.set(SerializerOptions.METHOD, SerialMethod.HTML);
            else if (method.contains("json") || method.contains("javascript")) {
                JsonSerialOptions jsopt = new JsonSerialOptions();
                jsopt.set(JsonOptions.FORMAT, jsonFormat);
                opt.set(SerializerOptions.METHOD, SerialMethod.JSON);
                opt.set(SerializerOptions.JSON, jsopt);
            }
            else if (method.contains("raw")) opt.set(SerializerOptions.METHOD, SerialMethod.RAW);
        }
        opt.set(SerializerOptions.INDENT, indent ? Options.YesNo.YES : Options.YesNo.NO);
        return opt;
    }

    private static SerializerOptions getSerializerOptions(String method, boolean indent) {
        return getSerializerOptions(method, indent, "basic");
    }

    private static SerializerOptions getSerializerOptions(String method) {
        return getSerializerOptions(method, false);
    }
}
