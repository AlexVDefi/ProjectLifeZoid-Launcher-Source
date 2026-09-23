package zombie.plz;

import java.util.ArrayList;

/**
 * Linear replacements for the two ScriptParser passes that dominate script loading.
 * Same output as vanilla on every input; see java-patch/README.md.
 */
public final class PLZScriptText {
    private PLZScriptText() {
    }

    /**
     * @return the text with block comments removed, or null for any input vanilla would not
     *     treat as a plain sequence of comments - a nested or unbalanced one. Vanilla deletes
     *     from the last {@code *\/} backwards and is NOT nesting-aware, so on those inputs its
     *     output is not what a nesting scan produces. Null hands the file back to it unchanged.
     */
    public static String stripComments(String s) {
        int n = s.length();
        int first = s.indexOf("/*");
        if (first == -1) {
            return s.indexOf("*/") == -1 ? s : null;
        }

        StringBuilder out = new StringBuilder(n);
        out.append(s, 0, first);
        boolean inComment = false;
        int i = first;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                if (inComment) {
                    return null;
                }
                inComment = true;
                i += 2;
            } else if (c == '*' && i + 1 < n && s.charAt(i + 1) == '/') {
                if (!inComment) {
                    return null;
                }
                inComment = false;
                i += 2;
            } else {
                if (!inComment) {
                    out.append(c);
                }
                i++;
            }
        }

        return inComment ? null : out.toString();
    }

    public static ArrayList<String> parseTokens(String s) {
        ArrayList<String> tokens = new ArrayList<>();
        int base = 0;

        while (true) {
            if (s.indexOf("}", base + 1) == -1) {
                String rest = s.substring(base).trim();
                if (!rest.isEmpty()) {
                    tokens.add(rest);
                }
                return tokens;
            }

            int depth = 0;
            int open = base;
            int closed = base;
            do {
                open = s.indexOf("{", open + 1);
                closed = s.indexOf("}", closed + 1);
                if ((closed >= open || closed == -1) && open != -1) {
                    closed = open;
                    depth++;
                } else {
                    open = closed;
                    depth--;
                }
            } while (depth > 0);

            if (open == -1) {
                return tokens;
            }

            tokens.add(s.substring(base, open + 1).trim());
            base = open + 1;
        }
    }
}
