import com.sun.source.util.JavacTask;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
public final class ParseJava {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null); var paths = Files.walk(Path.of(args[0]))) {
            var files = paths.filter(p -> p.toString().endsWith(".java")).map(Path::toFile).toList();
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                    manager.getJavaFileObjectsFromFiles(files));
            task.parse();
            for (var d : diagnostics.getDiagnostics()) { if (d.getKind() == Diagnostic.Kind.ERROR) { throw new AssertionError(d); } }
            System.out.println("Parsed " + files.size() + " Java source files (syntax only)");
        }
    }
}
