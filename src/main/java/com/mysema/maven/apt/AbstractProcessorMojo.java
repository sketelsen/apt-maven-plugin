/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.maven.apt;

import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.Scanner;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.plexus.build.incremental.BuildContext;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;

/**
 * Base class for AnnotationProcessorMojo implementations
 * 
 * @author tiwe
 * 
 */
public abstract class AbstractProcessorMojo extends AbstractMojo {

    private static final String JAVA_FILE_FILTER = "/*.java";
    private static final String[] ALL_JAVA_FILES_FILTER = new String[] { "**" + JAVA_FILE_FILTER };

    @Component
    private BuildContext buildContext;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter
    private String[] processors;

    @Parameter
    private String processor;

    @Parameter(defaultValue = "${project.build.sourceEncoding}", required = true)
    private String sourceEncoding;

    @Parameter
    private Map<String, String> options;

    @Parameter
    private Map<String, String> compilerOptions;

    /**
     * A list of inclusion package filters for the apt processor.
     * 
     * If not specified all sources will be used for apt processor
     * 
     * <pre>
     * e.g.:
     * &lt;includes&gt;
     * 	&lt;include&gt;com.mypackge.**.bo.**&lt;/include&gt;
     * &lt;/includes&gt;
     * </pre>
     * 
     * will include all files which match com/mypackge/ ** /bo/ ** / *.java
     */
    @Parameter
    private Set<String> includes = new HashSet<String>();

    @Parameter(defaultValue = "false")
    private boolean showWarnings;

    @Parameter(defaultValue = "false")
    private boolean logOnlyOnError;

    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> pluginArtifacts;

    @Parameter(defaultValue = "true")
    private boolean ignoreDelta;

    @VisibleForTesting
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    File projectBuildDirectory;

    @VisibleForTesting
    @Parameter(defaultValue = "${project.compileSourceRoots}", readonly = true, required = true)
    List<String> compileSourceRoots;

    @VisibleForTesting
    @Parameter(defaultValue = "${project.compileClasspathElements}", readonly = true, required = true)
    List<String> compileClasspathElements;

    @VisibleForTesting
    @Parameter(defaultValue = "${project.testCompileSourceRoots}", readonly = true, required = true)
    List<String> testCompileSourceRoots;

    @VisibleForTesting
    @Parameter(defaultValue = "${project.testClasspathElements}", readonly = true, required = true)
    List<String> testClasspathElements;

    private String buildCompileClasspath() {
        List<String> pathElements = null;

        if (isForTest()) {
            pathElements = testClasspathElements;
        } else {
            pathElements = compileClasspathElements;
        }

        if (pluginArtifacts != null) {
            for (Artifact a : pluginArtifacts) {
                if (a.getFile() != null) {
                    pathElements.add(a.getFile().getAbsolutePath());
                }
            }
        }

        if (pathElements.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        int i = 0;
        for (i = 0; i < pathElements.size() - 1; ++i) {
            result.append(pathElements.get(i)).append(File.pathSeparatorChar);
        }
        result.append(pathElements.get(i));
        return result.toString();
    }

    private String buildProcessor() {
        if (processors != null) {
            StringBuilder result = new StringBuilder();
            for (String processor : processors) {
                if (result.length() > 0) {
                    result.append(",");
                }
                result.append(processor);
            }
            return result.toString();
        } else if (processor != null) {
            return processor;
        } else {
            String error = "Either processor or processors need to be given";
            getLog().error(error);
            throw new IllegalArgumentException(error);
        }
    }

    private List<String> buildCompilerOptions(String processor, String compileClassPath, String outputDirectory) throws IOException {
        Map<String, String> compilerOpts = new LinkedHashMap<String, String>();

        // Default options
        compilerOpts.put("cp", compileClassPath);

        if (sourceEncoding != null) {
            compilerOpts.put("encoding", sourceEncoding);
        }

        compilerOpts.put("proc:only", null);
        compilerOpts.put("processor", processor);

        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                if (entry.getValue() != null) {
                    compilerOpts.put("A" + entry.getKey() + "=" + entry.getValue(), null);
                } else {
                    compilerOpts.put("A" + entry.getKey() + "=", null);
                }

            }
        }

        if (outputDirectory != null) {
            compilerOpts.put("s", outputDirectory);
        }

        if (!showWarnings) {
            compilerOpts.put("nowarn", null);
        }

        StringBuilder builder = new StringBuilder();
        for (File file : getSourceDirectories()) {
            if (builder.length() > 0) {
                builder.append(";");
            }
            builder.append(file.getCanonicalPath());
        }
        compilerOpts.put("sourcepath", builder.toString());

        // User options override default options
        if (compilerOptions != null) {
            compilerOpts.putAll(compilerOptions);
        }

        List<String> opts = new ArrayList<String>(compilerOpts.size() * 2);

        for (Map.Entry<String, String> compilerOption : compilerOpts.entrySet()) {
            opts.add("-" + compilerOption.getKey());
            String value = compilerOption.getValue();
            if (StringUtils.isNotBlank(value)) {
                opts.add(value);
            }
        }
        return opts;
    }

    private Set<File> filterFiles(boolean incremental, boolean hasDeletedFiles, Set<File> directories) {
        String[] filters = ALL_JAVA_FILES_FILTER;
        if (includes != null && !includes.isEmpty()) {
            filters = includes.toArray(new String[includes.size()]);
            for (int i = 0; i < filters.length; i++) {
                filters[i] = filters[i].replace('.', '/') + JAVA_FILE_FILTER;
            }
        }

        Set<File> files = new HashSet<File>();
        for (File directory : directories) {
            // support for incremental build in m2e context
            Scanner scanner = buildContext.newScanner(directory, hasDeletedFiles);
            scanner.setIncludes(filters);
            scanner.scan();
            String[] includedFiles = scanner.getIncludedFiles();

            if (includedFiles != null) {
                for (String includedFile : includedFiles) {
                    files.add(new File(scanner.getBasedir(), includedFile));
                }
            }
        }
        return files;
    }

    private boolean containsDeletedFiles(Set<File> directories) {
        String[] filters = ALL_JAVA_FILES_FILTER;
        if (includes != null && !includes.isEmpty()) {
            filters = includes.toArray(new String[includes.size()]);
            for (int i = 0; i < filters.length; i++) {
                filters[i] = filters[i].replace('.', '/') + JAVA_FILE_FILTER;
            }
        }
        for (File directory : directories) {
            Scanner scanner = buildContext.newDeleteScanner(directory);
            scanner.setIncludes(filters);
            scanner.scan();
            String[] includedFiles = scanner.getIncludedFiles();
            if (includedFiles != null && includedFiles.length > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add messages through the buildContext:
     * <ul>
     *   <li>cli build creates log output</li>
     *   <li>m2e build creates markers for eclipse</li>
     * </ul>
     * @param diagnostics
     * @param tmpFileToOutputFile
     */
    private void processDiagnostics(final List<Diagnostic<? extends JavaFileObject>> diagnostics, Function<File, File> tmpFileToOutputFile) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            JavaFileObject javaFileObject = diagnostic.getSource();
            if (javaFileObject != null) { // message was created without element parameter
                File file = tmpFileToOutputFile.apply(new File(javaFileObject.toUri().getPath()));
                Kind kind = diagnostic.getKind();
                int lineNumber = (int) diagnostic.getLineNumber();
                int columnNumber = (int) diagnostic.getColumnNumber();
                String message = diagnostic.getMessage(Locale.getDefault());
                switch (kind) {
                case ERROR:
                    buildContext.addMessage(file, lineNumber, columnNumber, message, BuildContext.SEVERITY_ERROR, null);
                    break;
                case WARNING:
                case MANDATORY_WARNING:
                    buildContext.addMessage(file, lineNumber, columnNumber, message, BuildContext.SEVERITY_WARNING, null);
                    break;
                case NOTE:
                case OTHER:
                default:
                    break;
                }
            }
        }
    }

    public void execute() throws MojoExecutionException {
        if (getOutputDirectory() == null) {
            return;
        }
        if ("true".equals(System.getProperty("maven.apt.skip"))) {
            return;
        }

        if (!getOutputDirectory().exists()) {
            getOutputDirectory().mkdirs();
        }

        // make sure to add compileSourceRoots also during configuration build in m2e context
        if (isForTest()) {
            project.addTestCompileSourceRoot(getOutputDirectory().getAbsolutePath());
        } else {
            project.addCompileSourceRoot(getOutputDirectory().getAbsolutePath());
        }

        Set<File> sourceDirectories = getSourceDirectories();

        getLog().debug("Using build context: " + buildContext);

        StandardJavaFileManager fileManager = null;

        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new MojoExecutionException("You need to run build with JDK or have tools.jar on the classpath."
                        + "If this occures during eclipse build make sure you run eclipse under JDK as well");
            }

            boolean incremental = buildContext.isIncremental();
            boolean hasDeletedFiles = containsDeletedFiles(sourceDirectories);
            Set<File> files = filterFiles(incremental, hasDeletedFiles, sourceDirectories);
            if (files.isEmpty()) {
                getLog().debug("No Java sources found (skipping)");
                return;
            }

            fileManager = compiler.getStandardFileManager(null, null, null);
            Iterable<? extends JavaFileObject> compilationUnits1 = fileManager.getJavaFileObjectsFromFiles(files);
            // clean all markers
            for (JavaFileObject javaFileObject : compilationUnits1) {
                buildContext.removeMessages(new File(javaFileObject.toUri().getPath()));
            }

            String compileClassPath = buildCompileClasspath();

            String processor = buildProcessor();

            File tempDirectory = new File(projectBuildDirectory, "apt" + System.currentTimeMillis());
            tempDirectory.mkdirs();
            String outputDirectory = tempDirectory.getAbsolutePath();

            List<String> compilerOptions = buildCompilerOptions(processor, compileClassPath, outputDirectory);

            Writer out = null;
            if (logOnlyOnError) {
                out = new StringWriter();
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<JavaFileObject>();
            try {
                CompilationTask task = compiler.getTask(out, fileManager, diagnosticCollector, compilerOptions, null, compilationUnits1);
                Future<Boolean> future = executor.submit(task);
                Boolean rv = future.get();

                if (Boolean.FALSE.equals(rv) && logOnlyOnError) {
                    getLog().error(out.toString());
                }
            } finally {
                executor.shutdown();
                boolean deleteFilesInOutputDirectory = hasDeletedFiles || !incremental;
                FileSync.syncFiles(deleteFilesInOutputDirectory, tempDirectory, getOutputDirectory());
                FileUtils.deleteDirectory(tempDirectory);

                final String tempDirectoryName = FilenameUtils.normalize(tempDirectory.getAbsolutePath());
                processDiagnostics(diagnosticCollector.getDiagnostics(), new Function<File, File>() {
                    @Override
                    public File apply(final File input) {
                        final String inputAbsolutePath = FilenameUtils.normalize(input.getAbsolutePath());
                        if (inputAbsolutePath.startsWith(tempDirectoryName)) {
                            return new File(getOutputDirectory(), inputAbsolutePath.replace(tempDirectoryName, ""));
                        }
                        return input;
                    }
                });
            }

            buildContext.refresh(getOutputDirectory());
        } catch (Exception e1) {
            getLog().error("execute error", e1);
            throw new MojoExecutionException(e1.getMessage(), e1);

        } finally {
            if (fileManager != null) {
                try {
                    fileManager.close();
                } catch (Exception e) {
                    getLog().warn("Unable to close fileManager", e);
                }
            }
        }
    }

    protected abstract File getOutputDirectory();

    protected Set<File> getSourceDirectories() {
        File outputDirectory = getOutputDirectory();
        String outputPath = outputDirectory.getAbsolutePath();
        Set<File> directories = new HashSet<File>();
        List<String> directoryNames = isForTest() ? testCompileSourceRoots : compileSourceRoots;
        for (String name : directoryNames) {
            File file = new File(name);
            if (!file.getAbsolutePath().equals(outputPath) && file.exists()) {
                directories.add(file);
            }
        }
        return directories;
    }

    protected boolean isForTest() {
        return false;
    }

    public void setBuildContext(BuildContext buildContext) {
        this.buildContext = buildContext;
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setProcessors(String[] processors) {
        this.processors = processors;
    }

    public void setProcessor(String processor) {
        this.processor = processor;
    }

    public void setSourceEncoding(String sourceEncoding) {
        this.sourceEncoding = sourceEncoding;
    }

    public void setOptions(Map<String, String> options) {
        this.options = options;
    }

    public void setCompilerOptions(Map<String, String> compilerOptions) {
        this.compilerOptions = compilerOptions;
    }

    public void setIncludes(Set<String> includes) {
        this.includes = includes;
    }

    public void setShowWarnings(boolean showWarnings) {
        this.showWarnings = showWarnings;
    }

    public void setLogOnlyOnError(boolean logOnlyOnError) {
        this.logOnlyOnError = logOnlyOnError;
    }

    public void setPluginArtifacts(List<Artifact> pluginArtifacts) {
        this.pluginArtifacts = pluginArtifacts;
    }

}
