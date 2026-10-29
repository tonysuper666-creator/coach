import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

public class CoachJvmPlugin implements Plugin<Project> {
    public void apply(Project project) {
        project.getPluginManager().apply("java-library");
        project.getExtensions().getByType(JavaPluginExtension.class)
            .getToolchain().getLanguageVersion().set(JavaLanguageVersion.of(17));
    }
}
