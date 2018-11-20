package com.dld.hll.protobuf.generator.maven.plugin;

import com.dld.hll.protobuf.generator.ProtoExecutor;
import lombok.Getter;
import lombok.Setter;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.io.File;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Mojo(name = "generate", defaultPhase = LifecyclePhase.INITIALIZE)
public class ProtoFileGenerator extends AbstractMojo {
    @Parameter
    private String projectName;
    @Parameter
    private String projectPath;
    @Parameter
    private String projectBasePath;
    @Parameter
    private String scanPackage;
    @Parameter
    private String interfaceArtifactId;
    @Parameter
    private String commentClass;
    @Parameter
    private String commentMethodName = "value";
    @Parameter
    private String generatePath;

    @Parameter
    private String extendsInterface;
    @Parameter
    private String namePattern;

    @Component
    private MavenProject project;
    @Component
    private PluginDescriptor descriptor;
    @Component
    private RepositorySystem repoSystem;
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    private String jarFile;


    public void execute() throws MojoExecutionException {
        // 加载环境
        loadClassRealm();

        // 生成proto文件
        generateProto();
    }

    /**
     * 加载依赖的环境
     * 查询要生成Proto文件的Jar包路径
     */
    private void loadClassRealm() throws MojoExecutionException {
        try {
            ClassRealm realm = descriptor.getClassRealm();

            // 将所有依赖的Jar包，添加到Maven环境中
            for (org.apache.maven.model.Dependency dependency : project.getDependencies()) {
                List<ArtifactResult> artifactResults = getAllRelatedArtifacts(dependency);
                for (ArtifactResult ar : artifactResults) {
                    realm.addURL(ar.getArtifact().getFile().toURI().toURL());

                    if (interfaceArtifactId != null && interfaceArtifactId.equals(ar.getArtifact().getArtifactId())) {
                        jarFile = ar.getArtifact().getFile().getAbsolutePath();
                    }
                }
            }

            // 检查是否存在指定 artifactId 的接口
            if (interfaceArtifactId != null && jarFile == null) {
                throw new MojoExecutionException(
                        "Could not find the interface with artifactId [" + interfaceArtifactId + "].");
            }

            // 将执行项目添加到Maven环境中
            if (interfaceArtifactId == null) {
                List<String> runtimeClasspathElements = project.getRuntimeClasspathElements();
                for (String element : runtimeClasspathElements) {
                    File elementFile = new File(element);
                    realm.addURL(elementFile.toURI().toURL());
                }
            }
        } catch (MalformedURLException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Could not load dependencies or current project classes.", e);
        }
    }

    private List<ArtifactResult> getAllRelatedArtifacts(org.apache.maven.model.Dependency dependency)
            throws MojoExecutionException {
        Artifact artifact = new DefaultArtifact(
                dependency.getGroupId(),
                dependency.getArtifactId(),
                dependency.getClassifier(),
                dependency.getType(),
                dependency.getVersion());

        ArtifactRequest artifactReq = new ArtifactRequest()
                .setRepositories(project.getRemotePluginRepositories())
                .setArtifact(artifact);

        CollectRequest collectRequest = new CollectRequest(new Dependency(artifact, JavaScopes.COMPILE),
                project.getRemotePluginRepositories());
        DependencyRequest dependencyReq = new DependencyRequest(collectRequest, null);

        List<ArtifactResult> artifactResults = new ArrayList<>();
        try {
            ArtifactResult ar = repoSystem.resolveArtifact(repoSession, artifactReq);
            artifactResults.add(ar);

            List<ArtifactResult> dar = repoSystem.resolveDependencies(repoSession, dependencyReq).getArtifactResults();
            artifactResults.addAll(dar);
        } catch (ArtifactResolutionException | DependencyResolutionException e) {
            throw new MojoExecutionException("Artifact " + dependency.getArtifactId() + " could not be resolved.", e);
        }
        return artifactResults;
    }

    /**
     * 配置环境，生成Proto文件
     */
    @SuppressWarnings("unchecked")
    private void generateProto() throws MojoExecutionException {
        try {
            ProtoExecutor.Builder builder = ProtoExecutor.newBuilder();

            // 不需要程序自行加载指定Jar包
            builder.setNeedLoadJarFile(false);

            // 配置 Builder
            if (hasText(projectName)) {
                builder.setProjectName(projectName);
            }
            if (hasText(projectPath)) {
                builder.setProjectPath(projectPath);
            }
            if (hasText(projectBasePath)) {
                builder.setProjectBasePath(projectBasePath);
            }
            if (hasText(scanPackage)) {
                builder.setScanPackage(scanPackage);
            }
            if (jarFile != null) {
                builder.setJarFile(jarFile);
            }
            if (hasText(commentClass) && hasText(commentMethodName)) {
                Class<?> comment = Class.forName(commentClass);
                if (!Annotation.class.isAssignableFrom(comment)) {
                    throw new MojoExecutionException("Comment class [" + commentClass + "] is not a annotation class.");
                }
                builder.setComment((Class<? extends Annotation>) comment, commentMethodName);
            }
            if (hasText(generatePath)) {
                builder.setGeneratePath(generatePath);
            }

            if (hasText(extendsInterface)) {
                builder.setExtendsInterface(Class.forName(extendsInterface));
            }
            if (hasText(namePattern)) {
                builder.setNamePattern(namePattern);
            }

            // 生成文件
            builder.build().executor();
        } catch (Exception e) {
            throw new MojoExecutionException("Could not Generate proto file.", e);
        }
    }

    private boolean hasText(String str) {
        if (str != null && str.length() > 0) {
            int strLen = str.length();
            for (int i = 0; i < strLen; i++) {
                if (!Character.isWhitespace(str.charAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }
}