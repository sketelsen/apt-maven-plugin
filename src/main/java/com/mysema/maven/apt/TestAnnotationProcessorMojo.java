/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.maven.apt;

import java.io.File;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * TestAnnotationProcessorMojo calls APT processors for code generation
 */
@Mojo(name = "test-process", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class TestAnnotationProcessorMojo extends AbstractProcessorMojo {

    @Parameter
    private File outputDirectory;

    @Parameter
    private File testOutputDirectory;

    @Override
    public File getOutputDirectory() {
        return testOutputDirectory != null ? testOutputDirectory : outputDirectory;
    }

    @Override
    protected boolean isForTest() {
        return true;
    }

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setTestOutputDirectory(File testOutputDirectory) {
        this.testOutputDirectory = testOutputDirectory;
    }

}
