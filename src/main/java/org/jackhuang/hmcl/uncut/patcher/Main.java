package org.jackhuang.hmcl.uncut.patcher;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class Main {
    static final String VERSION = "1.1.0";

    private Main() {
    }

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>();
        boolean gui = false;
        for (String arg : args) {
            if ("--gui".equals(arg)) {
                gui = true;
            } else {
                rest.add(arg);
            }
        }

        if (gui) {
            launchGui(rest);
            return;
        }
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            Gui.launch(null);
            return;
        }
        System.exit(run(args));
    }

    private static void launchGui(List<String> rest) {
        if (rest.size() > 1) {
            System.err.println("图形界面模式只接受一个文件参数");
            System.exit(2);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("当前环境没有图形界面，请改用命令行模式：");
            usage();
            System.exit(2);
        }
        Path preset = null;
        if (!rest.isEmpty()) {
            try {
                preset = Path.of(rest.get(0));
            } catch (Exception e) {
                System.err.println("无效的路径：" + rest.get(0));
                System.exit(2);
                return;
            }
        }
        Gui.launch(preset);
    }

    private static int run(String[] args) {
        Path input = null;
        Path output = null;
        boolean check = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> {
                    usage();
                    return 0;
                }
                case "-o", "--output" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("缺少 -o 的参数");
                        return 2;
                    }
                    output = Path.of(args[++i]);
                }
                case "--check" -> check = true;
                case "-v", "--version" -> {
                    System.out.println("HMCL-Uncut-Patcher " + VERSION);
                    return 0;
                }
                default -> {
                    if (input != null) {
                        System.err.println("多余的参数：" + args[i]);
                        return 2;
                    }
                    input = Path.of(args[i]);
                }
            }
        }

        if (input == null) {
            usage();
            return 2;
        }
        if (!Files.isRegularFile(input)) {
            System.err.println("文件不存在：" + input);
            return 1;
        }

        try {
            byte[] data = Files.readAllBytes(input);
            System.out.println("读取：" + input + "（" + data.length + " 字节）");

            Consumer<String> log = line -> System.out.println("  " + line);
            Patcher.Result result = Patcher.patch(data, log);

            System.out.println("格式：" + Patcher.describe(result));
            if (result.version() != null) {
                System.out.println("版本：" + result.version());
            }
            System.out.println("门禁：RegionUtils." + ClassPatcher.METHOD_NAME + ClassPatcher.METHOD_DESC
                    + " 已改为恒返回 false");

            if (check) {
                System.out.println("检查通过（未写出文件）");
                return 0;
            }

            Path target = output != null ? output : Patcher.defaultOutput(input);
            Files.write(target, result.data());
            System.out.println("输出：" + target);
            System.out.println("大小：" + result.data().length + " 字节");
            System.out.println("SHA-256：" + Util.sha256(result.data()));
            return 0;
        } catch (ClassPatcher.AlreadyPatchedException e) {
            System.err.println("× " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("× " + e.getMessage());
            return 1;
        }
    }

    private static void usage() {
        System.out.println("""
                HMCL-Uncut-Patcher %s

                图形界面（也可以直接双击 jar）：
                  java -jar HMCL-Uncut-Patcher.jar
                  java -jar HMCL-Uncut-Patcher.jar --gui [要预选的文件]

                命令行：
                  java -jar HMCL-Uncut-Patcher.jar <输入文件> [-o 输出文件] [--check]

                支持：
                  HMCL-*.jar    通用 jar
                  HMCL-*.exe    Windows 自解压程序
                  HMCL-*.sh     Linux / macOS 自解压脚本
                  HMCL-*.deb    Debian / Ubuntu 安装包

                说明：
                  只修改内嵌 jar 里的 org/jackhuang/hmcl/util/RegionUtils.class，
                  把 isIsChinaMainlandNetwork() 改成恒返回 false，其余内容原样保留。
                  补丁后的文件会以 -uncut 后缀命名，原文件不会被修改。
                """.formatted(VERSION));
    }
}
