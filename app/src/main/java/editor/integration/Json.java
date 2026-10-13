package editor.integration;
import java.util.*;

/** Small JSON codec for the private wire protocol. Rejects duplicates, non-finite values and trailing input. */
public final class Json {
    private Json() {}
    public static Object parse(String source) { return new Parser(source).parse(); }
    public static String write(Object value) {
        StringBuilder out=new StringBuilder();write(value,out,0);return out.toString();
    }
    private static void write(Object v,StringBuilder out,int depth) {
        if(depth>64)throw new IllegalArgumentException("JSON nesting limit");
        if(v==null)out.append("null");
        else if(v instanceof String s) {
            out.append('"');for(char c:s.toCharArray())switch(c) {
                case '"' -> out.append("\\\"");case '\\' -> out.append("\\\\");case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");case '\t' -> out.append("\\t");
                default -> { if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c); }
            }out.append('"');
        } else if(v instanceof Number n) {
            if(!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("non-finite number");out.append(n);
        } else if(v instanceof Boolean)out.append(v);
        else if(v instanceof Map<?,?> map) {
            out.append('{');boolean first=true;
            for(var e:map.entrySet()) { if(!(e.getKey() instanceof String))throw new IllegalArgumentException("JSON key");if(!first)out.append(',');first=false;write(e.getKey(),out,depth+1);out.append(':');write(e.getValue(),out,depth+1); }out.append('}');
        } else if(v instanceof List<?> list) {
            out.append('[');boolean first=true;for(Object item:list) { if(!first)out.append(',');first=false;write(item,out,depth+1); }out.append(']');
        } else throw new IllegalArgumentException("unsupported JSON type");
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("JSON object expected");return (Map<String,Object>)value;
    }
    public static String string(Map<String,Object> map,String key) {
        if(!(map.get(key) instanceof String value))throw new IllegalArgumentException("string expected: "+key);return value;
    }
    public static int integer(Map<String,Object> map,String key) {
        if(!(map.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()!=n.intValue())
            throw new IllegalArgumentException("integer expected: "+key);return n.intValue();
    }
    public static List<?> list(Map<String,Object> map,String key) {
        if(!(map.get(key) instanceof List<?> list))throw new IllegalArgumentException("list expected: "+key);return list;
    }
    private static final class Parser {
        final String s;int pos;
        Parser(String s) { this.s=Objects.requireNonNull(s); }
        Object parse() { Object value=value(0);space();if(pos!=s.length())bad();return value; }
        void space() { while(pos<s.length() && " \t\n\r".indexOf(s.charAt(pos))>=0)pos++; }
        void bad() { throw new IllegalArgumentException("Invalid JSON at "+pos); }
        boolean take(char c) { space();if(pos<s.length() && s.charAt(pos)==c) {pos++;return true;}return false; }
        Object value(int depth) {
            if(depth>64)bad();space();if(pos>=s.length())bad();char c=s.charAt(pos);
            if(c=='"')return string();
            if(take('{')) {
                Map<String,Object> map=new LinkedHashMap<>();if(take('}'))return map;
                do { space();if(pos>=s.length() || s.charAt(pos)!='"')bad();String key=string();if(!take(':') || map.containsKey(key))bad();map.put(key,value(depth+1)); }while(take(','));
                if(!take('}'))bad();return map;
            }
            if(take('[')) {
                List<Object> list=new ArrayList<>();if(take(']'))return list;do { list.add(value(depth+1)); }while(take(','));if(!take(']'))bad();return list;
            }
            for(String word:new String[]{"true","false","null"})if(s.startsWith(word,pos)) { pos+=word.length();return word.equals("null")?null:word.equals("true"); }
            int start=pos;if(pos<s.length() && s.charAt(pos)=='-')pos++;
            if(pos>=s.length())bad();
            if(s.charAt(pos)=='0')pos++;else { if(s.charAt(pos)<'1'||s.charAt(pos)>'9')bad();digits(); }
            if(pos<s.length() && s.charAt(pos)=='.') { pos++;int mark=pos;digits();if(mark==pos)bad(); }
            if(pos<s.length() && (s.charAt(pos)=='e'||s.charAt(pos)=='E')) {
                pos++;if(pos<s.length() && (s.charAt(pos)=='+'||s.charAt(pos)=='-'))pos++;int mark=pos;digits();if(mark==pos)bad();
            }
            String text=s.substring(start,pos);
            try { double n=Double.parseDouble(text);if(!Double.isFinite(n))bad();return n; }
            catch(NumberFormatException e) { bad();return null; }
        }
        void digits() { while(pos<s.length() && s.charAt(pos)>='0' && s.charAt(pos)<='9')pos++; }
        String string() {
            pos++;StringBuilder out=new StringBuilder();
            while(pos<s.length()) {
                char c=s.charAt(pos++);if(c=='"')return out.toString();if(c<32)bad();
                if(c=='\\') {
                    if(pos==s.length())bad();c=s.charAt(pos++);
                    switch(c) {
                        case '"','\\','/' -> out.append(c);case 'b' -> out.append('\b');case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');case 'r' -> out.append('\r');case 't' -> out.append('\t');
                        case 'u' -> { if(pos+4>s.length())bad();try { out.append((char)Integer.parseInt(s.substring(pos,pos+4),16)); }catch(NumberFormatException e) {bad();}pos+=4; }
                        default -> bad();
                    }
                } else out.append(c);
            }
            bad();return null;
        }
    }
}
