package io.klaude.tool.builtin;

public final class ProcessFixture {
    // 按参数向 stdout 写入指定数量的 ASCII 字节
    public static void main(String[] args) throws Exception {
        if (args[0].equals("sleep")) {
            Thread.sleep(Long.parseLong(args[1]));
            return;
        }
        int count = Integer.parseInt(args[0]);
        for (int index = 0; index < count; index++) {
            System.out.write('x');
        }
    }

    // 禁止实例化测试子进程入口
    private ProcessFixture() {}
}
