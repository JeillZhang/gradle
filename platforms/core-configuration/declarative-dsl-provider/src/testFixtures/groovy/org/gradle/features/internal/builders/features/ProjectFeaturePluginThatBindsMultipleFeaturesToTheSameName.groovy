/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.features.internal.builders.features

import org.gradle.features.annotations.BindsProjectFeature
import org.gradle.features.binding.ProjectFeatureApplicationContext
import org.gradle.features.binding.ProjectFeatureApplyAction
import org.gradle.features.binding.ProjectFeatureBinding
import org.gradle.features.binding.ProjectFeatureBindingBuilder
import org.gradle.features.internal.builders.definitions.ProjectFeatureDefinitionClassBuilder

class ProjectFeaturePluginThatBindsMultipleFeaturesToTheSameName extends ProjectFeaturePluginClassBuilder {
    private final String anotherBindingTypeClassName

    ProjectFeaturePluginThatBindsMultipleFeaturesToTheSameName(ProjectFeatureDefinitionClassBuilder definition, String anotherBindingTypeClassName) {
        super(definition)
        this.anotherBindingTypeClassName = anotherBindingTypeClassName
    }

    @Override
    protected String getClassContent() {
        def simpleBindingTypeName = bindingTypeClassName.tokenize('.').last()
        def simpleAnotherBindingTypeName = anotherBindingTypeClassName.tokenize('.').last()

        return """
            package org.gradle.test;

            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            import ${BindsProjectFeature.class.name};
            import ${ProjectFeatureBindingBuilder.class.name};
            import static ${ProjectFeatureBindingBuilder.class.name}.bindingToTargetDefinition;
            import ${ProjectFeatureBinding.class.name};
            import ${ProjectFeatureApplyAction.class.name};
            import ${ProjectFeatureApplicationContext.class.name};

            @${BindsProjectFeature.class.simpleName}(${projectFeaturePluginClassName}.Binding.class)
            public class ${projectFeaturePluginClassName} implements Plugin<Project> {

                static class Binding implements ${ProjectFeatureBinding.class.simpleName} {
                    @Override public void bind(${ProjectFeatureBindingBuilder.class.simpleName} builder) {
                        builder.${bindingMethodName}(
                            "${name}",
                            ${definition.publicTypeClassName}.class,
                            ${bindingTypeClassName}.class,
                            ${simpleBindingTypeName}ApplyAction.class
                        )
                        ${maybeDeclareDefinitionImplementationType()}
                        ${maybeDeclareBuildModelImplementationType()}
                        ${maybeDeclareBindingModifiers()};

                        builder.${bindingMethodName}(
                            "${name}",
                            ${definition.publicTypeClassName}.class,
                            ${anotherBindingTypeClassName}.class,
                            ${simpleAnotherBindingTypeName}ApplyAction.class
                        );
                    }
                }

                static abstract class BaseApplyAction<T extends ${org.gradle.features.binding.Definition.class.name}> implements ${ProjectFeatureApplyAction.class.name}<${definition.publicTypeClassName}, ${definition.getBuildModelFullPublicClassName()}, T> {
                    @javax.inject.Inject public BaseApplyAction() { }

                    ${servicesInjection}

                    abstract protected String getTaskName();

                    @Override
                    public void apply(${ProjectFeatureApplicationContext.class.name} context, ${definition.publicTypeClassName} definition, ${definition.getBuildModelFullPublicClassName()} model, T parent) {
                        System.out.println("Binding ${definition.publicTypeClassName}");
                        System.out.println("${name} model class: " + model.getClass().getSimpleName());
                        System.out.println("${name} parent model class: " + context.getBuildModel(parent).getClass().getSimpleName());

                        ${definition.buildModelMapping.replace('services.', '')}

                        getTaskRegistrar().register(getTaskName(), task -> {
                            task.doLast(t -> {
                                ${definition.displayDefinitionPropertyValues()}
                                ${definition.displayModelPropertyValues()}
                            });
                        });
                    }
                }

                static abstract class ${simpleBindingTypeName}ApplyAction extends BaseApplyAction<${parentTypeForApplyAction}> {
                    @javax.inject.Inject public ${simpleBindingTypeName}ApplyAction() { }
                    @Override protected String getTaskName() { return "print${definition.publicTypeClassName}1Configuration"; }
                }

                static abstract class ${simpleAnotherBindingTypeName}ApplyAction extends BaseApplyAction<${anotherBindingTypeClassName}> {
                    @javax.inject.Inject public ${simpleAnotherBindingTypeName}ApplyAction() { }
                    @Override protected String getTaskName() { return "print${definition.publicTypeClassName}2Configuration"; }
                }

                @Override
                public void apply(Project project) {

                }
            }
        """
    }
}
