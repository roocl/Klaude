package io.klaude.transport;

import java.util.List;
import java.util.regex.Pattern;

public final class SubscriptionMatcher {
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");

    // 禁止实例化订阅匹配工具类
    private SubscriptionMatcher() {
    }

    // 检查 event type 是否命中任一平台兼容 glob topic
    public static boolean matchesTopic(String eventType, List<String> topics) {
        return topics.stream().anyMatch(topic -> globPattern(topic).matcher(eventType).matches());
    }

    // 检查 event run ID 是否符合 global 或精确 run scope
    public static boolean matchesScope(String runId, String scope) {
        if (scope.equals("global")) {
            return true;
        }
        return scope.startsWith("run:") && java.util.Objects.equals(runId, scope.substring(4));
    }

    // 将支持的 glob 子集编译为锚定正则
    private static Pattern globPattern(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < glob.length(); index++) {
            char current = glob.charAt(index);
            if (current == '*') {
                regex.append(".*");
            } else if (current == '?') {
                regex.append('.');
            } else if (current == '[') {
                int end = glob.indexOf(']', index + 1);
                if (end < 0) {
                    regex.append("\\[");
                    continue;
                }
                String characters = glob.substring(index + 1, end);
                regex.append('[');
                if (characters.startsWith("!")) {
                    regex.append('^');
                    characters = characters.substring(1);
                }
                regex.append(characters.replace("\\", "\\\\")).append(']');
                index = end;
            } else {
                if ("\\.^$|(){}+".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), WINDOWS ? Pattern.CASE_INSENSITIVE : 0);
    }
}
