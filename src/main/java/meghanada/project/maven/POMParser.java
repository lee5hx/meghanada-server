package meghanada.project.maven;

import com.google.common.base.Strings;
import meghanada.project.ProjectParseException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;

class POMParser {
    private static final Logger log = LogManager.getLogger(POMParser.class);

    private final File projectRoot;

    public POMParser(File projectRoot) {
        this.projectRoot = projectRoot;
    }

    private static String getPOMProperties(POMInfo pomInfo, String value) {
        if (value != null && value.contains("$")) {
            int startIdx = value.indexOf('$');
            int endIdx = value.indexOf('}');
            String key = value.substring(startIdx + 2, endIdx);
            String replace = value.substring(startIdx, endIdx + 1);
            String newValue = pomInfo.properties.getProperty(key);
            if (newValue != null) {
                value = value.replace(replace, newValue);
            }
        }
        return value;
    }

    POMInfo parsePom(File pom) throws ProjectParseException {

        try {
            if (!pom.isAbsolute()) {
                pom = pom.getCanonicalFile();
            }
            try (BufferedReader reader = Files.newBufferedReader(pom.toPath(), UTF_8)) {
                final POMInfo pomInfo = new POMInfo(pom.getParent());

                final MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
                final Model mavenModel = xpp3Reader.read(reader);

                final Parent parent = mavenModel.getParent();
                if (parent != null) {
                    String relativePath = parent.getRelativePath();
                    File parentPom = new File(pom.getParent(), relativePath).getCanonicalFile();

                    if (parentPom.exists()) {
                        log.debug("start  parent {}", parentPom);
                        POMInfo parentPOMInfo = parsePom(parentPom);
                        log.debug("finish parent {}", parentPom);
                        // add all
                        pomInfo.putAll(parentPOMInfo);
                    }
                }

                final String groupId = mavenModel.getGroupId();
                if (groupId != null) {
                    pomInfo.groupId = groupId;
                }
                final String artifactId = mavenModel.getArtifactId();
                if (artifactId != null) {
                    pomInfo.artifactId = artifactId;
                }
                final String version = mavenModel.getVersion();
                if (version != null) {
                    pomInfo.version = version;
                }

                final Properties modelProperties = mavenModel.getProperties();
                if (modelProperties != null) {
                    if (groupId != null) {
                        modelProperties.put("project.groupId", groupId);
                    }
                    if (artifactId != null) {
                        modelProperties.put("project.artifactId", artifactId);
                    }
                    if (version != null) {
                        modelProperties.put("project.version", version);
                    }

                    for (final String key : modelProperties.stringPropertyNames()) {
                        String value = modelProperties.getProperty(key);
                        if (value != null) {
                            pomInfo.properties.setProperty(key, value);
                        }
                    }
                    this.replacePOMProperties(pomInfo);
                }

                final Build build = mavenModel.getBuild();
                this.parseBuild(pomInfo, build);

                return pomInfo;
            }
        } catch (IOException | XmlPullParserException e) {
            throw new ProjectParseException(e);
        }
    }

    private void replacePOMProperties(POMInfo pomInfo) {
        for (String key : pomInfo.properties.stringPropertyNames()) {
            String val = pomInfo.properties.getProperty(key);
            val = POMParser.getPOMProperties(pomInfo, val);
            val = POMParser.getPOMProperties(pomInfo, val);
            if (val != null) {
                pomInfo.properties.setProperty(key, val);
            }
        }
    }

    private File normalize(POMInfo pomInfo, String src) {
        src = getPOMProperties(pomInfo, src);
        File file = new File(src);
        if (!file.isAbsolute()) {
            file = new File(this.projectRoot, src);
        }
        return file;
    }

    private void parseBuild(POMInfo pomInfo, Build build) {
        if (build != null) {
            {
                String src = build.getSourceDirectory();
                if (!Strings.isNullOrEmpty(src)) {
                    pomInfo.sourceDirectory.add(normalize(pomInfo, src));
                }
                for (Resource resource : build.getResources()) {
                    pomInfo.resourceDirectories.add(normalize(pomInfo, resource.getDirectory()));
                }
                String out = build.getOutputDirectory();
                if (!Strings.isNullOrEmpty(out)) {
                    pomInfo.outputDirectory = normalize(pomInfo, out);
                }
            }
            {
                String src = build.getTestSourceDirectory();
                if (!Strings.isNullOrEmpty(src)) {
                    pomInfo.testSourceDirectory.add(normalize(pomInfo, src));
                }
                for (Resource resource : build.getTestResources()) {
                    pomInfo.testResourceDirectories.add(normalize(pomInfo, resource.getDirectory()));
                }
                String out = build.getTestOutputDirectory();
                if (!Strings.isNullOrEmpty(out)) {
                    pomInfo.testOutputDirectory = normalize(pomInfo, out);
                }
            }

            parsePlugins(pomInfo, build);
        }
    }

    private void parsePlugins(POMInfo pomInfo, Build build) {
        for (Plugin plugin : build.getPlugins()) {
            if (plugin.getArtifactId().equals("build-helper-maven-plugin")) {
                for (PluginExecution pluginExecution : plugin.getExecutions()) {
                    Object conf = pluginExecution.getConfiguration();
                    if (conf != null && conf instanceof Xpp3Dom) {
                        Xpp3Dom confDom = (Xpp3Dom) conf;
                        Xpp3Dom sources = confDom.getChild("sources");
                        if (sources != null) {
                            Xpp3Dom[] children = sources.getChildren();
                            if (children != null) {
                                for (Xpp3Dom s : sources.getChildren()) {
                                    String value = s.getValue();
                                    if (!Strings.isNullOrEmpty(value)) {
                                        pomInfo.sourceDirectory.add(normalize(pomInfo, value));
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (plugin.getArtifactId().equals("maven-compiler-plugin")) {
                Object conf = plugin.getConfiguration();
                if (conf != null && conf instanceof Xpp3Dom) {
                    Xpp3Dom confDom = (Xpp3Dom) conf;
                    Xpp3Dom source = confDom.getChild("source");
                    if (source != null) {
                        pomInfo.compileSource = source.getValue();
                    }

                    Xpp3Dom target = confDom.getChild("target");
                    if (target != null) {
                        pomInfo.compileTarget = target.getValue();
                    }

                }
            }
        }
    }

}
