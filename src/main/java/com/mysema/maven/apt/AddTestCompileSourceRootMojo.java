/*
 * Copyright (c) 2012 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.maven.apt;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * AddTestCompileSourceRootMojo adds the folder for generated tests sources to the POM
 */
@Mojo(name="add-test-sources", defaultPhase=LifecyclePhase.GENERATE_SOURCES, threadSafe=true)
public class AddTestCompileSourceRootMojo extends AbstractMojo {
    
    @Parameter(defaultValue="${project}", readonly=true, required=true)
    private MavenProject project;
    
    @Parameter
    private File outputDirectory;
    
    @Parameter
    private File testOutputDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File directory = testOutputDirectory != null ? testOutputDirectory : outputDirectory;
        if (!directory.exists()) {
            directory.mkdirs();
        }
        project.addTestCompileSourceRoot(directory.getAbsolutePath());
    }

    public void setProject(MavenProject project) {
        this.project = project;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setTestOutputDirectory(File testOutputDirectory) {
        this.testOutputDirectory = testOutputDirectory;
    }
    
}
