package codes.rafael.bytecodeupdate;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(name = "update-bytecode",
        defaultPhase = LifecyclePhase.COMPILE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public class BytecodeUpdateMavenPlugin extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter
    private List<String> artifacts;

    @Parameter
    private List<String> includes;

    @Parameter
    private List<String> excludes;

    @Parameter
    private String oldPackage;

    @Parameter
    private String newPackage;

    @Parameter(defaultValue = "8", required = true)
    private int javaVersion;

    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    private String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<URL> urls = new ArrayList<>();
        for (Artifact artifact : project.getArtifacts()) {
            try {
                urls.add(artifact.getFile().toURI().toURL());
            } catch (MalformedURLException e) {
                throw new MojoFailureException("Failed ro resolve location " + artifact.getFile(), e);
            }
        }
        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
        Remapper remapper = oldPackage == null && newPackage == null
                ? new Remapper() { }
                : new PackageNameRemapper(oldPackage.replace('.', '/'), newPackage.replace('.', '/'));
        for (Artifact artifact : project.getArtifacts()) {
            String value = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
            boolean isRelevant = false;
            if (artifacts == null || artifacts.isEmpty()) {
                isRelevant = true;
            } else {
                for (String candidate : artifacts) {
                    if (value.matches(candidate)) {
                        isRelevant = true;
                        break;
                    }
                }
            }
            if (!isRelevant) {
                continue;
            }
            try {
                JarFile file = new JarFile(artifact.getFile());
                try {
                    Enumeration<JarEntry> enumeration = file.entries();
                    while (enumeration.hasMoreElements()) {
                        JarEntry entry = enumeration.nextElement();
                        if (entry.getName().endsWith(".class")) {
                            boolean isIncluded = false;
                            if (includes == null || includes.isEmpty()) {
                                isIncluded = true;
                            } else {
                                for (String include : includes) {
                                    if (entry.getName().matches(include)) {
                                        isIncluded = true;
                                        break;
                                    }
                                }
                            }
                            if (excludes != null && !excludes.isEmpty()) {
                                for (String exclude : excludes) {
                                    if (entry.getName().matches(exclude)) {
                                        isIncluded = false;
                                        break;
                                    }
                                }
                            }
                            if (!isIncluded) {
                                continue;
                            }
                            InputStream inputStream = file.getInputStream(entry);
                            try {
                                ClassReader classReader = new ClassReader(inputStream);
                                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                                    @Override
                                    protected String getCommonSuperClass(String type1, String type2) {
                                        if (remapper instanceof PackageNameRemapper) {
                                            type1 = ((PackageNameRemapper) remapper).reverse(type1);
                                            type2 = ((PackageNameRemapper) remapper).reverse(type2);
                                        }
                                        Class<?> class1;
                                        try {
                                            class1 = Class.forName(type1.replace('/', '.'), false, classLoader);
                                        } catch (Exception e) {
                                            throw new TypeNotPresentException(type1, e);
                                        }
                                        Class<?> class2;
                                        try {
                                            class2 = Class.forName(type2.replace('/', '.'), false, classLoader);
                                        } catch (Exception e) {
                                            throw new TypeNotPresentException(type2, e);
                                        }
                                        if (class1.isAssignableFrom(class2)) {
                                            return type1;
                                        }
                                        if (class2.isAssignableFrom(class1)) {
                                            return type2;
                                        }
                                        if (class1.isInterface() || class2.isInterface()) {
                                            return "java/lang/Object";
                                        } else {
                                            do {
                                                class1 = class1.getSuperclass();
                                            } while (!class1.isAssignableFrom(class2));
                                            return class1.getName().replace('.', '/');
                                        }
                                    }
                                };
                                classReader.accept(new ClassVisitor(Opcodes.ASM6, new ClassRemapper(classWriter, remapper)) {
                                    @Override
                                    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                                        super.visit(javaVersion + 44, access, name, signature, superName, interfaces);
                                    }
                                }, 0);
                                File output = new File(outputDirectory, remapper.map(entry.getName().substring(0, entry.getName().length() - ".class".length()) + ".class"));
                                if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) {
                                    throw new MojoFailureException("Cannot create folder for " + output);
                                }
                                OutputStream outputStream = new FileOutputStream(output);
                                try {
                                    outputStream.write(classWriter.toByteArray());
                                } finally {
                                    outputStream.close();
                                }
                            } finally {
                                inputStream.close();
                            }
                        }
                    }
                } finally {
                    file.close();
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to upgrade classes", e);
            }
        }
    }
}
