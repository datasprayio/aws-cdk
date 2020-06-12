package io.linguarobot.aws.cdk.maven;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr353.JSR353Module;
import com.google.common.collect.ImmutableMap;
import io.linguarobot.aws.cdk.*;
import org.apache.commons.lang3.ObjectUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class CloudDefinition {

    private final List<StackDefinition> stacks;

    private CloudDefinition(List<StackDefinition> stacks) {
        this.stacks = stacks;
    }

    @Nonnull
    public List<StackDefinition> getStacks() {
        return stacks;
    }

    @Override
    public String toString() {
        return "CloudDefinition{" +
                "stacks=" + stacks +
                '}';
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JSR353Module());

    public static CloudDefinition create(Path cloudAssemblyDirectory) {
        CloudManifest manifest;
        try {
            manifest = CloudManifest.create(cloudAssemblyDirectory);
        } catch (IOException e) {
            throw new CdkPluginException("Failed to read the cloud manifest", e);
        }

        return manifest.getArtifacts().entrySet().stream()
                .filter(artifact -> artifact.getValue().getType() == ArtifactType.STACK)
                .map(artifact -> {
                    String artifactId = artifact.getKey();
                    StackArtifact stackArtifact = (StackArtifact) artifact.getValue();
                    String stackName = ObjectUtils.firstNonNull(stackArtifact.getProperties().getStackName(), artifactId);
                    Path templateFile = cloudAssemblyDirectory.resolve(stackArtifact.getProperties().getTemplateFile());
                    Integer requiredToolkitStackVersion = Optional.ofNullable(stackArtifact.getProperties().getRequiredToolkitStackVersion())
                            .map(Number::intValue)
                            .orElse(null);
                    Map<String, Object> template = readTemplate(templateFile);
                    Map<String, ParameterDefinition> parameters = getParameterDefinitions(template);
                    List<AssetMetadata> assets = stackArtifact.getMetadata().values().stream()
                            .flatMap(List::stream)
                            .filter(metadata -> metadata.getType() == MetadataType.ASSET)
                            .map(metadata -> (AssetMetadata) metadata)
                            .collect(Collectors.toList());
                    Map<String, Map<String, Object>> resources = (Map<String, Map<String, Object>>) template.getOrDefault("Resources", ImmutableMap.of());
                    return StackDefinition.builder()
                            .withStackName(stackName)
                            .withTemplateFile(templateFile)
                            .withEnvironment(stackArtifact.getEnvironment())
                            .withRequiredToolkitStackVersion(requiredToolkitStackVersion)
                            .withParameters(parameters)
                            .withParameterValues(stackArtifact.getProperties().getParameters())
                            .withAssets(assets)
                            .withResources(resources)
                            .build();
                })
                .collect(Collectors.collectingAndThen(Collectors.toList(), stacks -> new CloudDefinition(Collections.unmodifiableList(stacks))));
    }

    private static Map<String, Object> readTemplate(Path template) {
        try {
            return OBJECT_MAPPER.readValue(template.toFile(), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new CdkPluginException("Failed to read the stack template: " + template);
        }
    }

    private static Map<String, ParameterDefinition> getParameterDefinitions(Map<String, Object> template) {
        Map<String, Map<String, Object>> parameters = (Map<String, Map<String, Object>>) template.getOrDefault("Parameters", Collections.emptyMap());

        return parameters.entrySet().stream()
                .map(parameter -> {
                    String name = parameter.getKey();
                    String defaultValue = (String) parameter.getValue().get("Default");
                    return new ParameterDefinition(name, defaultValue);
                })
                .collect(Collectors.toMap(ParameterDefinition::getName, Function.identity()));
    }
}