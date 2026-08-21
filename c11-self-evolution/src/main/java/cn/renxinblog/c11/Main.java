package cn.renxinblog.c11;
import java.util.List;
public final class Main { public static void main(String[] a) { List<String> t = List.of("检查", "构建", "验证"); System.out.println("[1] traces=2"); System.out.println("[2] mined trajectory: " + t); System.out.println("[3] recall for 发布: " + String.join(" -> ", t)); } }
