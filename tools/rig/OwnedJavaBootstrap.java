import java.nio.file.*;
import java.io.*;
/** Independent stdin/log adapter. Installed Prism handles its own actual launch protocol. */
public final class OwnedJavaBootstrap {
 public static void main(String[] args)throws Exception {
  System.setOut(new PrintStream(Files.newOutputStream(Path.of(args[1])),true,java.nio.charset.StandardCharsets.UTF_8));System.setErr(System.out);
  System.setIn(Files.newInputStream(Path.of(args[0])));
  Class.forName("org.prismlauncher.EntryPoint").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
 }
}
