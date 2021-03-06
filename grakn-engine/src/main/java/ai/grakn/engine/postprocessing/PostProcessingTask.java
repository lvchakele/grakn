/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 */

package ai.grakn.engine.postprocessing;

import ai.grakn.GraknConfigKey;
import ai.grakn.Keyspace;
import ai.grakn.concept.ConceptId;
import ai.grakn.engine.tasks.BackgroundTask;
import ai.grakn.engine.tasks.manager.TaskConfiguration;
import ai.grakn.engine.tasks.manager.TaskState;
import ai.grakn.util.REST;
import ai.grakn.util.Schema;
import com.codahale.metrics.Timer.Context;
import mjson.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * <p>
 *     Task that control when postprocessing starts.
 * </p>
 *
 * <p>
 *     This task begins only if enough time has passed (configurable) since the last time a job was added.
 * </p>
 *
 * @author alexandraorth, fppt
 */
public class PostProcessingTask extends BackgroundTask {
    private static final Logger LOG = LoggerFactory.getLogger(PostProcessingTask.class);
    private static final String JOB_FINISHED = "Post processing Job [{}] completed for indeces and ids: [{}]";

    /**
     * Apply {@link ai.grakn.concept.Attribute} post processing jobs the concept ids in the provided configuration
     *
     * @return True if successful.
     */
    @Override
    public boolean start() {
        try (Context context = metricRegistry()
                .timer(name(PostProcessingTask.class, "execution")).time()) {
            Map<String, Set<ConceptId>> allToPostProcess = getPostProcessingJobs(Schema.BaseType.ATTRIBUTE, configuration());

            allToPostProcess.forEach((conceptIndex, conceptIds) -> {
                Context contextSingle = metricRegistry()
                        .timer(name(PostProcessingTask.class, "execution-single")).time();
                try {
                    Keyspace keyspace = Keyspace.of(configuration().json().at(REST.Request.KEYSPACE_PARAM).asString());
                    int maxRetry = engineConfiguration().getProperty(GraknConfigKey.LOADER_REPEAT_COMMITS);

                    GraknTxMutators.runMutationWithRetry(factory(), keyspace, maxRetry,
                            (graph) -> postProcessor().mergeDuplicateConcepts(graph, conceptIndex, conceptIds));
                } finally {
                    contextSingle.stop();
                }
            });

            LOG.debug(JOB_FINISHED, Schema.BaseType.ATTRIBUTE.name(), allToPostProcess);

            return true;
        }
    }

    /**
     * Extract a map of concept indices to concept ids from the provided configuration
     *
     * @param type Type of concept to extract. This correlates to the key in the provided configuration.
     * @param configuration Configuration from which to extract the configuration.
     * @return Map of concept indices to ids that has been extracted from the provided configuration.
     */
    private static Map<String,Set<ConceptId>> getPostProcessingJobs(Schema.BaseType type, TaskConfiguration configuration) {
        return configuration.json().at(REST.Request.COMMIT_LOG_FIXING).at(type.name()).asJsonMap().entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().asList().stream().map(o -> ConceptId.of(o.toString())).collect(Collectors.toSet())
        ));
    }

    /**
     * Helper method which creates PP Task States.
     *
     * @param creator The class which is creating the task
     * @return The executable postprocessing task state
     */
    public static TaskState createTask(Class creator) {
        return TaskState.of(PostProcessingTask.class, creator.getName());
    }

    /**
     * Helper method which creates the task config needed in order to execute a PP task
     *
     * @param keyspace The keyspace of the graph to execute this on.
     * @param config The config which contains the concepts to post process
     * @return The task configuration encapsulating the above details in a manner executable by the task runner
     */
    public static TaskConfiguration createConfig(Keyspace keyspace, String config){
        Json postProcessingConfiguration = Json.object();
        postProcessingConfiguration.set(REST.Request.KEYSPACE_PARAM, keyspace.getValue());
        postProcessingConfiguration.set(REST.Request.COMMIT_LOG_FIXING, Json.read(config).at(REST.Request.COMMIT_LOG_FIXING));
        return TaskConfiguration.of(postProcessingConfiguration);
    }
}