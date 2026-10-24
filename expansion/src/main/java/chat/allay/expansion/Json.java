package chat.allay.expansion;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Just enough JSON to write a schema, with no library behind it. */
final class Json {
  private Json() {}

  static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  private static void write(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String text) {
      quote(text, out);
    } else if (value instanceof Number || value instanceof Boolean) {
      out.append(value);
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      Iterator<? extends Map.Entry<?, ?>> entries = map.entrySet().iterator();
      while (entries.hasNext()) {
        Map.Entry<?, ?> entry = entries.next();
        quote(String.valueOf(entry.getKey()), out);
        out.append(':');
        write(entry.getValue(), out);
        if (entries.hasNext()) out.append(',');
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) out.append(',');
        write(list.get(i), out);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException("can't write " + value.getClass().getName() + " as JSON");
    }
  }

  private static void quote(String text, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }
}
