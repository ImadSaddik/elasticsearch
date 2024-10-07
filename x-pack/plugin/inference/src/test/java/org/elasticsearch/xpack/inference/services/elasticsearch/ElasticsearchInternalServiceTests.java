/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 *
 * This file was contributed to by a Generative AI
 */

package org.elasticsearch.xpack.inference.services.elasticsearch;

import org.apache.logging.log4j.Level;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;
import org.elasticsearch.inference.ChunkedInferenceServiceResults;
import org.elasticsearch.inference.ChunkingOptions;
import org.elasticsearch.inference.EmptyTaskSettings;
import org.elasticsearch.inference.EndpointVersions;
import org.elasticsearch.inference.InferenceResults;
import org.elasticsearch.inference.InferenceServiceExtension;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xpack.core.action.util.QueryPage;
import org.elasticsearch.xpack.core.inference.action.InferenceAction;
import org.elasticsearch.xpack.core.inference.results.ErrorChunkedInferenceResults;
import org.elasticsearch.xpack.core.inference.results.InferenceChunkedSparseEmbeddingResults;
import org.elasticsearch.xpack.core.inference.results.InferenceChunkedTextEmbeddingFloatResults;
import org.elasticsearch.xpack.core.ml.action.GetTrainedModelsAction;
import org.elasticsearch.xpack.core.ml.action.InferModelAction;
import org.elasticsearch.xpack.core.ml.action.InferTrainedModelDeploymentAction;
import org.elasticsearch.xpack.core.ml.action.PutTrainedModelAction;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelConfig;
import org.elasticsearch.xpack.core.ml.inference.TrainedModelPrefixStrings;
import org.elasticsearch.xpack.core.ml.inference.results.ErrorInferenceResults;
import org.elasticsearch.xpack.core.ml.inference.results.InferenceChunkedTextExpansionResultsTests;
import org.elasticsearch.xpack.core.ml.inference.results.MlChunkedTextEmbeddingFloatResults;
import org.elasticsearch.xpack.core.ml.inference.results.MlChunkedTextEmbeddingFloatResultsTests;
import org.elasticsearch.xpack.core.ml.inference.results.MlChunkedTextExpansionResults;
import org.elasticsearch.xpack.core.ml.inference.results.MlTextEmbeddingResults;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TextEmbeddingConfigUpdate;
import org.elasticsearch.xpack.core.ml.inference.trainedmodel.TokenizationConfigUpdate;
import org.elasticsearch.xpack.core.utils.FloatConversionUtils;
import org.elasticsearch.xpack.inference.InferencePlugin;
import org.elasticsearch.xpack.inference.services.ServiceFields;
import org.junit.After;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.inference.services.elasticsearch.ElasticsearchInternalService.MULTILINGUAL_E5_SMALL_MODEL_ID;
import static org.elasticsearch.xpack.inference.services.elasticsearch.ElasticsearchInternalService.MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86;
import static org.elasticsearch.xpack.inference.services.elasticsearch.ElasticsearchInternalService.NAME;
import static org.elasticsearch.xpack.inference.services.elasticsearch.ElasticsearchInternalService.OLD_ELSER_SERVICE_NAME;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ElasticsearchInternalServiceTests extends ESTestCase {

    String randomInferenceEntityId = randomAlphaOfLength(10);

    private static ThreadPool threadPool;

    @Before
    public void setUpThreadPool() {
        threadPool = createThreadPool(InferencePlugin.inferenceUtilityExecutor(Settings.EMPTY));
    }

    @After
    public void shutdownThreadPool() {
        terminate(threadPool);
    }

    public void testParseRequestConfig() {
        // Null model variant
        var service = createService(mock(Client.class));
        var config = new HashMap<String, Object>();
        config.put(ModelConfigurations.SERVICE, ElasticsearchInternalService.NAME);
        config.put(
            ModelConfigurations.SERVICE_SETTINGS,
            new HashMap<>(
                Map.of(ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS, 1, ElasticsearchInternalServiceSettings.NUM_THREADS, 4)
            )
        );

        ActionListener<Model> modelListener = ActionListener.<Model>wrap(
            model -> fail("Model parsing should have failed"),
            e -> assertThat(e, instanceOf(IllegalArgumentException.class))
        );

        var taskType = randomFrom(TaskType.TEXT_EMBEDDING, TaskType.RERANK, TaskType.SPARSE_EMBEDDING);
        service.parseRequestConfig(randomInferenceEntityId, taskType, config, EndpointVersions.FIRST_ENDPOINT_VERSION, modelListener);
    }

    public void testParseRequestConfig_Misconfigured() {
        // Non-existent model variant
        {
            var service = createService(mock(Client.class));
            var config = new HashMap<String, Object>();
            config.put(ModelConfigurations.SERVICE, ElasticsearchInternalService.NAME);
            config.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS, 1, ElasticsearchInternalServiceSettings.NUM_THREADS, 4)
                )
            );

            ActionListener<Model> modelListener = ActionListener.<Model>wrap(
                model -> fail("Model parsing should have failed"),
                e -> assertThat(e, instanceOf(IllegalArgumentException.class))
            );

            var taskType = randomFrom(TaskType.TEXT_EMBEDDING, TaskType.RERANK, TaskType.SPARSE_EMBEDDING);
            service.parseRequestConfig(randomInferenceEntityId, taskType, config, EndpointVersions.FIRST_ENDPOINT_VERSION, modelListener);
        }

        // Invalid config map
        {
            var service = createService(mock(Client.class));
            var config = new HashMap<String, Object>();
            config.put(ModelConfigurations.SERVICE, ElasticsearchInternalService.NAME);
            config.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS, 1, ElasticsearchInternalServiceSettings.NUM_THREADS, 4)
                )
            );
            config.put("not_a_valid_config_setting", randomAlphaOfLength(10));

            ActionListener<Model> modelListener = ActionListener.<Model>wrap(
                model -> fail("Model parsing should have failed"),
                e -> assertThat(e, instanceOf(ElasticsearchStatusException.class))
            );

            var taskType = randomFrom(TaskType.TEXT_EMBEDDING, TaskType.RERANK, TaskType.SPARSE_EMBEDDING);
            service.parseRequestConfig(randomInferenceEntityId, taskType, config, EndpointVersions.FIRST_ENDPOINT_VERSION, modelListener);
        }
    }

    public void testParseRequestConfig_E5() {
        {
            var service = createService(mock(Client.class), Set.of("Aarch64"));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        MULTILINGUAL_E5_SMALL_MODEL_ID
                    )
                )
            );

            var e5ServiceSettings = new MultilingualE5SmallInternalServiceSettings(1, 4, MULTILINGUAL_E5_SMALL_MODEL_ID, null);

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.TEXT_EMBEDDING,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                getE5ModelVerificationActionListener(e5ServiceSettings)
            );
        }

        {
            var service = createService(mock(Client.class), Set.of("linux-x86_64"));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        ElasticsearchInternalService.MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86
                    )
                )
            );

            var e5ServiceSettings = new MultilingualE5SmallInternalServiceSettings(
                1,
                4,
                ElasticsearchInternalService.MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86,
                null
            );

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.TEXT_EMBEDDING,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                getE5ModelVerificationActionListener(e5ServiceSettings)
            );
        }

        // Invalid service settings
        {
            var service = createService(mock(Client.class), Set.of("Aarch64"));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        MULTILINGUAL_E5_SMALL_MODEL_ID,
                        "not_a_valid_service_setting",
                        randomAlphaOfLength(10)
                    )
                )
            );

            ActionListener<Model> modelListener = ActionListener.<Model>wrap(
                model -> fail("Model parsing should have failed"),
                e -> assertThat(e, instanceOf(ElasticsearchStatusException.class))
            );

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.TEXT_EMBEDDING,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                modelListener
            );
        }
    }

    public void testParseRequestConfig_elser() {
        // General happy case
        {
            Client mockClient = mock(Client.class);
            when(mockClient.threadPool()).thenReturn(threadPool);
            var service = createService(mockClient);
            var config = new HashMap<String, Object>();
            config.put(ModelConfigurations.SERVICE, OLD_ELSER_SERVICE_NAME);
            config.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        ElserModels.ELSER_V2_MODEL
                    )
                )
            );

            var elserServiceSettings = new ElserInternalServiceSettings(1, 4, ElserModels.ELSER_V2_MODEL, null);

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.SPARSE_EMBEDDING,
                config,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                getElserModelVerificationActionListener(
                    elserServiceSettings,
                    null,
                    "The [elser] service is deprecated and will be removed in a future release. Use the [elasticsearch] service "
                        + "instead, with [model_id] set to [.elser_model_2] in the [service_settings]"
                )
            );
        }

        // null model ID returns elser model for the provided platform (not linux)
        {
            Client mockClient = mock(Client.class);
            when(mockClient.threadPool()).thenReturn(threadPool);
            var service = createService(mockClient);
            var config = new HashMap<String, Object>();
            config.put(ModelConfigurations.SERVICE, OLD_ELSER_SERVICE_NAME);
            config.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS, 1, ElasticsearchInternalServiceSettings.NUM_THREADS, 4)
                )
            );

            var elserServiceSettings = new ElserInternalServiceSettings(1, 4, ElserModels.ELSER_V2_MODEL, null);

            String criticalWarning =
                "Putting elasticsearch service inference endpoints (including elser service) without a model_id field is"
                    + " deprecated and will be removed in a future release. Please specify a model_id field.";
            String warnWarning =
                "The [elser] service is deprecated and will be removed in a future release. Use the [elasticsearch] service "
                    + "instead, with [model_id] set to [.elser_model_2] in the [service_settings]";
            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.SPARSE_EMBEDDING,
                config,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                getElserModelVerificationActionListener(elserServiceSettings, criticalWarning, warnWarning)
            );
            assertWarnings(true, new DeprecationWarning(DeprecationLogger.CRITICAL, criticalWarning));
        }

        // Invalid service settings
        {
            Client mockClient = mock(Client.class);
            when(mockClient.threadPool()).thenReturn(threadPool);
            var service = createService(mockClient);
            var config = new HashMap<String, Object>();
            config.put(ModelConfigurations.SERVICE, OLD_ELSER_SERVICE_NAME);
            config.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        ElserModels.ELSER_V2_MODEL,
                        "not_a_valid_service_setting",
                        randomAlphaOfLength(10)
                    )
                )
            );

            ActionListener<Model> modelListener = ActionListener.<Model>wrap(
                model -> fail("Model parsing should have failed"),
                e -> assertThat(e, instanceOf(ElasticsearchStatusException.class))
            );

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.SPARSE_EMBEDDING,
                config,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                modelListener
            );
        }
    }

    @SuppressWarnings("unchecked")
    public void testParseRequestConfig_Rerank() {
        // with task settings
        {
            var client = mock(Client.class);
            doAnswer(invocation -> {
                var listener = (ActionListener<GetTrainedModelsAction.Response>) invocation.getArguments()[2];
                listener.onResponse(
                    new GetTrainedModelsAction.Response(new QueryPage<>(List.of(mock(TrainedModelConfig.class)), 1, mock(ParseField.class)))
                );
                return null;
            }).when(client).execute(Mockito.same(GetTrainedModelsAction.INSTANCE), any(), any());

            when(client.threadPool()).thenReturn(threadPool);

            var service = createService(client);
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        "foo"
                    )
                )
            );
            var returnDocs = randomBoolean();
            settings.put(
                ModelConfigurations.OLD_TASK_SETTINGS,
                new HashMap<>(Map.of(CustomElandRerankTaskSettings.RETURN_DOCUMENTS, returnDocs))
            );

            ActionListener<Model> modelListener = ActionListener.<Model>wrap(model -> {
                assertThat(model, instanceOf(CustomElandRerankModel.class));
                assertThat(model.getTaskSettings(), instanceOf(CustomElandRerankTaskSettings.class));
                assertThat(model.getServiceSettings(), instanceOf(CustomElandInternalServiceSettings.class));
                assertEquals(returnDocs, ((CustomElandRerankTaskSettings) model.getTaskSettings()).returnDocuments());
            }, e -> { fail("Model parsing failed " + e.getMessage()); });

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.RERANK,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                modelListener
            );
        }
    }

    @SuppressWarnings("unchecked")
    public void testParseRequestConfig_Rerank_DefaultTaskSettings() {
        // with task settings
        {
            var client = mock(Client.class);
            doAnswer(invocation -> {
                var listener = (ActionListener<GetTrainedModelsAction.Response>) invocation.getArguments()[2];
                listener.onResponse(
                    new GetTrainedModelsAction.Response(new QueryPage<>(List.of(mock(TrainedModelConfig.class)), 1, mock(ParseField.class)))
                );
                return null;
            }).when(client).execute(Mockito.same(GetTrainedModelsAction.INSTANCE), any(), any());

            when(client.threadPool()).thenReturn(threadPool);

            var service = createService(client);
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        "foo"
                    )
                )
            );

            ActionListener<Model> modelListener = ActionListener.<Model>wrap(model -> {
                assertThat(model, instanceOf(CustomElandRerankModel.class));
                assertThat(model.getTaskSettings(), instanceOf(CustomElandRerankTaskSettings.class));
                assertThat(model.getServiceSettings(), instanceOf(CustomElandInternalServiceSettings.class));
                assertEquals(Boolean.TRUE, ((CustomElandRerankTaskSettings) model.getTaskSettings()).returnDocuments());
            }, e -> { fail("Model parsing failed " + e.getMessage()); });

            service.parseRequestConfig(
                randomInferenceEntityId,
                TaskType.RERANK,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION,
                modelListener
            );
        }
    }

    @SuppressWarnings("unchecked")
    public void testParseRequestConfig_SparseEmbedding() {
        var client = mock(Client.class);
        doAnswer(invocation -> {
            var listener = (ActionListener<GetTrainedModelsAction.Response>) invocation.getArguments()[2];
            listener.onResponse(
                new GetTrainedModelsAction.Response(new QueryPage<>(List.of(mock(TrainedModelConfig.class)), 1, mock(ParseField.class)))
            );
            return null;
        }).when(client).execute(Mockito.same(GetTrainedModelsAction.INSTANCE), any(), any());

        when(client.threadPool()).thenReturn(threadPool);

        var service = createService(client);
        var settings = new HashMap<String, Object>();
        settings.put(
            ModelConfigurations.SERVICE_SETTINGS,
            new HashMap<>(
                Map.of(
                    ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                    1,
                    ElasticsearchInternalServiceSettings.NUM_THREADS,
                    4,
                    ElasticsearchInternalServiceSettings.MODEL_ID,
                    "foo"
                )
            )
        );

        ActionListener<Model> modelListener = ActionListener.<Model>wrap(model -> {
            assertThat(model, instanceOf(CustomElandModel.class));
            assertThat(model.getTaskSettings(), instanceOf(EmptyTaskSettings.class));
            assertThat(model.getServiceSettings(), instanceOf(CustomElandInternalServiceSettings.class));
        }, e -> { fail("Model parsing failed " + e.getMessage()); });

        service.parseRequestConfig(
            randomInferenceEntityId,
            TaskType.SPARSE_EMBEDDING,
            settings,
            EndpointVersions.FIRST_ENDPOINT_VERSION,
            modelListener
        );
    }

    private ActionListener<Model> getE5ModelVerificationActionListener(MultilingualE5SmallInternalServiceSettings e5ServiceSettings) {
        return ActionListener.<Model>wrap(model -> {
            assertEquals(
                new MultilingualE5SmallModel(
                    randomInferenceEntityId,
                    TaskType.TEXT_EMBEDDING,
                    ElasticsearchInternalService.NAME,
                    e5ServiceSettings,
                    EndpointVersions.FIRST_ENDPOINT_VERSION
                ),
                model
            );
        }, e -> { fail("Model parsing failed " + e.getMessage()); });
    }

    private ActionListener<Model> getElserModelVerificationActionListener(
        ElserInternalServiceSettings elserServiceSettings,
        String criticalWarning,
        String warnWarning
    ) {
        return ActionListener.wrap(model -> {
            assertWarnings(
                true,
                new DeprecationWarning(DeprecationLogger.CRITICAL, criticalWarning),
                new DeprecationWarning(Level.WARN, warnWarning)
            );
            assertEquals(
                new ElserInternalModel(
                    randomInferenceEntityId,
                    TaskType.SPARSE_EMBEDDING,
                    NAME,
                    elserServiceSettings,
                    ElserMlNodeTaskSettings.DEFAULT,
                    EndpointVersions.FIRST_ENDPOINT_VERSION
                ),
                model
            );
        }, e -> { fail("Model parsing failed " + e.getMessage()); });
    }

    public void testParsePersistedConfig() {

        // Null model variant
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ServiceFields.SIMILARITY,
                        SimilarityMeasure.L2_NORM.toString()
                    )
                )
            );

            expectThrows(
                IllegalArgumentException.class,
                () -> service.parsePersistedConfig(
                    randomInferenceEntityId,
                    TaskType.TEXT_EMBEDDING,
                    settings,
                    EndpointVersions.FIRST_ENDPOINT_VERSION
                )
            );

        }

        // Invalid model variant
        // because this is a persisted config, we assume that the model does exist, even though it doesn't. In practice, the trained models
        // API would throw an exception when the model is used
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        "invalid"
                    )
                )
            );

            CustomElandEmbeddingModel parsedModel = (CustomElandEmbeddingModel) service.parsePersistedConfig(
                randomInferenceEntityId,
                TaskType.TEXT_EMBEDDING,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
            var elandServiceSettings = new CustomElandInternalTextEmbeddingServiceSettings(1, 4, "invalid", null);
            assertEquals(
                new CustomElandEmbeddingModel(
                    randomInferenceEntityId,
                    TaskType.TEXT_EMBEDDING,
                    ElasticsearchInternalService.NAME,
                    elandServiceSettings,
                    EndpointVersions.FIRST_ENDPOINT_VERSION
                ),
                parsedModel
            );
        }

        // Valid model variant
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        MULTILINGUAL_E5_SMALL_MODEL_ID,
                        ServiceFields.DIMENSIONS,
                        1
                    )
                )
            );

            var e5ServiceSettings = new MultilingualE5SmallInternalServiceSettings(1, 4, MULTILINGUAL_E5_SMALL_MODEL_ID, null);

            MultilingualE5SmallModel parsedModel = (MultilingualE5SmallModel) service.parsePersistedConfig(
                randomInferenceEntityId,
                TaskType.TEXT_EMBEDDING,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
            assertEquals(
                new MultilingualE5SmallModel(
                    randomInferenceEntityId,
                    TaskType.TEXT_EMBEDDING,
                    ElasticsearchInternalService.NAME,
                    e5ServiceSettings,
                    EndpointVersions.FIRST_ENDPOINT_VERSION
                ),
                parsedModel
            );
        }

        // Invalid config map
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS, 1, ElasticsearchInternalServiceSettings.NUM_THREADS, 4)
                )
            );
            settings.put("not_a_valid_config_setting", randomAlphaOfLength(10));

            var taskType = randomFrom(TaskType.TEXT_EMBEDDING, TaskType.RERANK, TaskType.SPARSE_EMBEDDING);
            expectThrows(
                IllegalArgumentException.class,
                () -> service.parsePersistedConfig(randomInferenceEntityId, taskType, settings, EndpointVersions.FIRST_ENDPOINT_VERSION)
            );
        }

        // Invalid service settings
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        "not_a_valid_service_setting",
                        randomAlphaOfLength(10)
                    )
                )
            );
            var taskType = randomFrom(TaskType.TEXT_EMBEDDING, TaskType.RERANK, TaskType.SPARSE_EMBEDDING);
            expectThrows(
                IllegalArgumentException.class,
                () -> service.parsePersistedConfig(randomInferenceEntityId, taskType, settings, EndpointVersions.FIRST_ENDPOINT_VERSION)
            );
        }
    }

    @SuppressWarnings("unchecked")
    public void testChunkInfer_e5() {
        var mlTrainedModelResults = new ArrayList<InferenceResults>();
        mlTrainedModelResults.add(MlChunkedTextEmbeddingFloatResultsTests.createRandomResults());
        mlTrainedModelResults.add(MlChunkedTextEmbeddingFloatResultsTests.createRandomResults());
        mlTrainedModelResults.add(new ErrorInferenceResults(new RuntimeException("boom")));
        var response = new InferModelAction.Response(mlTrainedModelResults, "foo", true);

        ThreadPool threadpool = new TestThreadPool("test");
        Client client = mock(Client.class);
        when(client.threadPool()).thenReturn(threadpool);
        doAnswer(invocationOnMock -> {
            var listener = (ActionListener<InferModelAction.Response>) invocationOnMock.getArguments()[2];
            listener.onResponse(response);
            return null;
        }).when(client).execute(same(InferModelAction.INSTANCE), any(InferModelAction.Request.class), any(ActionListener.class));

        var model = new MultilingualE5SmallModel(
            "foo",
            TaskType.TEXT_EMBEDDING,
            "e5",
            new MultilingualE5SmallInternalServiceSettings(1, 1, "cross-platform", null),
            EndpointVersions.FIRST_ENDPOINT_VERSION
        );
        var service = createService(client);

        var gotResults = new AtomicBoolean();
        var resultsListener = ActionListener.<List<ChunkedInferenceServiceResults>>wrap(chunkedResponse -> {
            assertThat(chunkedResponse, hasSize(3));
            assertThat(chunkedResponse.get(0), instanceOf(InferenceChunkedTextEmbeddingFloatResults.class));
            var result1 = (InferenceChunkedTextEmbeddingFloatResults) chunkedResponse.get(0);
            assertEquals(
                ((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(0)).getChunks().size(),
                result1.getChunks().size()
            );
            assertEquals(
                ((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(0)).getChunks().get(0).matchedText(),
                result1.getChunks().get(0).matchedText()
            );
            assertArrayEquals(
                (FloatConversionUtils.floatArrayOf(
                    ((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(0)).getChunks().get(0).embedding()
                )),
                result1.getChunks().get(0).embedding(),
                0.0001f
            );
            assertThat(chunkedResponse.get(1), instanceOf(InferenceChunkedTextEmbeddingFloatResults.class));
            var result2 = (InferenceChunkedTextEmbeddingFloatResults) chunkedResponse.get(1);
            // assertEquals(((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(1)).getChunks(), result2.getChunks());

            assertEquals(
                ((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(1)).getChunks().size(),
                result2.getChunks().size()
            );
            assertEquals(
                ((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(1)).getChunks().get(0).matchedText(),
                result2.getChunks().get(0).matchedText()
            );
            assertArrayEquals(
                (FloatConversionUtils.floatArrayOf(
                    ((MlChunkedTextEmbeddingFloatResults) mlTrainedModelResults.get(1)).getChunks().get(0).embedding()
                )),
                result2.getChunks().get(0).embedding(),
                0.0001f
            );

            var result3 = (ErrorChunkedInferenceResults) chunkedResponse.get(2);
            assertThat(result3.getException(), instanceOf(RuntimeException.class));
            assertThat(result3.getException().getMessage(), containsString("boom"));
            gotResults.set(true);
        }, ESTestCase::fail);

        service.chunkedInfer(
            model,
            null,
            List.of("foo", "bar"),
            Map.of(),
            InputType.SEARCH,
            new ChunkingOptions(null, null),
            InferenceAction.Request.DEFAULT_TIMEOUT,
            ActionListener.runAfter(resultsListener, () -> terminate(threadpool))
        );

        if (gotResults.get() == false) {
            terminate(threadpool);
        }
        assertTrue("Listener not called", gotResults.get());
    }

    @SuppressWarnings("unchecked")
    public void testChunkInfer_Sparse() {
        var mlTrainedModelResults = new ArrayList<InferenceResults>();
        mlTrainedModelResults.add(InferenceChunkedTextExpansionResultsTests.createRandomResults());
        mlTrainedModelResults.add(InferenceChunkedTextExpansionResultsTests.createRandomResults());
        mlTrainedModelResults.add(new ErrorInferenceResults(new RuntimeException("boom")));
        var response = new InferModelAction.Response(mlTrainedModelResults, "foo", true);

        ThreadPool threadpool = new TestThreadPool("test");
        Client client = mock(Client.class);
        when(client.threadPool()).thenReturn(threadpool);
        doAnswer(invocationOnMock -> {
            var listener = (ActionListener<InferModelAction.Response>) invocationOnMock.getArguments()[2];
            listener.onResponse(response);
            return null;
        }).when(client).execute(same(InferModelAction.INSTANCE), any(InferModelAction.Request.class), any(ActionListener.class));

        var model = new CustomElandModel(
            "foo",
            TaskType.SPARSE_EMBEDDING,
            "elasticsearch",
            new ElasticsearchInternalServiceSettings(1, 1, "model-id", null),
            EndpointVersions.FIRST_ENDPOINT_VERSION
        );
        var service = createService(client);

        var gotResults = new AtomicBoolean();
        var resultsListener = ActionListener.<List<ChunkedInferenceServiceResults>>wrap(chunkedResponse -> {
            assertThat(chunkedResponse, hasSize(3));
            assertThat(chunkedResponse.get(0), instanceOf(InferenceChunkedSparseEmbeddingResults.class));
            var result1 = (InferenceChunkedSparseEmbeddingResults) chunkedResponse.get(0);
            assertEquals(((MlChunkedTextExpansionResults) mlTrainedModelResults.get(0)).getChunks(), result1.getChunkedResults());
            assertThat(chunkedResponse.get(1), instanceOf(InferenceChunkedSparseEmbeddingResults.class));
            var result2 = (InferenceChunkedSparseEmbeddingResults) chunkedResponse.get(1);
            assertEquals(((MlChunkedTextExpansionResults) mlTrainedModelResults.get(1)).getChunks(), result2.getChunkedResults());
            var result3 = (ErrorChunkedInferenceResults) chunkedResponse.get(2);
            assertThat(result3.getException(), instanceOf(RuntimeException.class));
            assertThat(result3.getException().getMessage(), containsString("boom"));
            gotResults.set(true);
        }, ESTestCase::fail);

        service.chunkedInfer(
            model,
            null,
            List.of("foo", "bar"),
            Map.of(),
            InputType.SEARCH,
            new ChunkingOptions(null, null),
            InferenceAction.Request.DEFAULT_TIMEOUT,
            ActionListener.runAfter(resultsListener, () -> terminate(threadpool))
        );

        if (gotResults.get() == false) {
            terminate(threadpool);
        }
        assertTrue("Listener not called", gotResults.get());
    }

    @SuppressWarnings("unchecked")
    public void testChunkInferSetsTokenization() {
        var expectedSpan = new AtomicInteger();
        var expectedWindowSize = new AtomicReference<Integer>();

        Client client = mock(Client.class);
        ThreadPool threadpool = new TestThreadPool("test");
        try {
            when(client.threadPool()).thenReturn(threadpool);
            doAnswer(invocationOnMock -> {
                var request = (InferTrainedModelDeploymentAction.Request) invocationOnMock.getArguments()[1];
                assertThat(request.getUpdate(), instanceOf(TokenizationConfigUpdate.class));
                var update = (TokenizationConfigUpdate) request.getUpdate();
                assertEquals(update.getSpanSettings().span(), expectedSpan.get());
                assertEquals(update.getSpanSettings().maxSequenceLength(), expectedWindowSize.get());
                return null;
            }).when(client)
                .execute(
                    same(InferTrainedModelDeploymentAction.INSTANCE),
                    any(InferTrainedModelDeploymentAction.Request.class),
                    any(ActionListener.class)
                );

            var model = new MultilingualE5SmallModel(
                "foo",
                TaskType.TEXT_EMBEDDING,
                "e5",
                new MultilingualE5SmallInternalServiceSettings(1, 1, "cross-platform", null),
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
            var service = createService(client);

            expectedSpan.set(-1);
            expectedWindowSize.set(null);
            service.chunkedInfer(
                model,
                List.of("foo", "bar"),
                Map.of(),
                InputType.SEARCH,
                null,
                InferenceAction.Request.DEFAULT_TIMEOUT,
                ActionListener.wrap(r -> fail("unexpected result"), e -> fail(e.getMessage()))
            );

            expectedSpan.set(-1);
            expectedWindowSize.set(256);
            service.chunkedInfer(
                model,
                List.of("foo", "bar"),
                Map.of(),
                InputType.SEARCH,
                new ChunkingOptions(256, null),
                InferenceAction.Request.DEFAULT_TIMEOUT,
                ActionListener.wrap(r -> fail("unexpected result"), e -> fail(e.getMessage()))
            );
        } finally {
            terminate(threadpool);
        }
    }

    public void testParsePersistedConfig_Rerank() {
        // with task settings
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        "foo"
                    )
                )
            );
            settings.put(ElasticsearchInternalServiceSettings.MODEL_ID, "foo");
            var returnDocs = randomBoolean();
            settings.put(
                ModelConfigurations.OLD_TASK_SETTINGS,
                new HashMap<>(Map.of(CustomElandRerankTaskSettings.RETURN_DOCUMENTS, returnDocs))
            );

            var model = service.parsePersistedConfig(
                randomInferenceEntityId,
                TaskType.RERANK,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
            assertThat(model.getTaskSettings(), instanceOf(CustomElandRerankTaskSettings.class));
            assertEquals(returnDocs, ((CustomElandRerankTaskSettings) model.getTaskSettings()).returnDocuments());
        }

        // without task settings
        {
            var service = createService(mock(Client.class));
            var settings = new HashMap<String, Object>();
            settings.put(
                ModelConfigurations.SERVICE_SETTINGS,
                new HashMap<>(
                    Map.of(
                        ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                        1,
                        ElasticsearchInternalServiceSettings.NUM_THREADS,
                        4,
                        ElasticsearchInternalServiceSettings.MODEL_ID,
                        "foo"
                    )
                )
            );
            settings.put(ElasticsearchInternalServiceSettings.MODEL_ID, "foo");

            var model = service.parsePersistedConfig(
                randomInferenceEntityId,
                TaskType.RERANK,
                settings,
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
            assertThat(model.getTaskSettings(), instanceOf(CustomElandRerankTaskSettings.class));
            assertTrue(((CustomElandRerankTaskSettings) model.getTaskSettings()).returnDocuments());
        }
    }

    public void testParseRequestConfigEland_PreservesTaskType() {
        var client = mock(Client.class);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            ActionListener<GetTrainedModelsAction.Response> listener = (ActionListener<GetTrainedModelsAction.Response>) invocationOnMock
                .getArguments()[2];
            listener.onResponse(
                new GetTrainedModelsAction.Response(new QueryPage<>(List.of(mock(TrainedModelConfig.class)), 1, mock(ParseField.class)))
            );
            return Void.TYPE;
        }).when(client).execute(eq(GetTrainedModelsAction.INSTANCE), any(), any());
        when(client.threadPool()).thenReturn(threadPool);

        var service = createService(client);
        var settings = new HashMap<String, Object>();
        settings.put(
            ModelConfigurations.SERVICE_SETTINGS,
            new HashMap<>(
                Map.of(
                    ElasticsearchInternalServiceSettings.NUM_ALLOCATIONS,
                    1,
                    ElasticsearchInternalServiceSettings.NUM_THREADS,
                    4,
                    ElasticsearchInternalServiceSettings.MODEL_ID,
                    "custom-model"
                )
            )
        );

        var taskType = randomFrom(EnumSet.of(TaskType.RERANK, TaskType.TEXT_EMBEDDING, TaskType.SPARSE_EMBEDDING));
        CustomElandModel expectedModel = getCustomElandModel(taskType);

        PlainActionFuture<Model> listener = new PlainActionFuture<>();
        service.parseRequestConfig(randomInferenceEntityId, taskType, settings, EndpointVersions.FIRST_ENDPOINT_VERSION, listener);
        var model = listener.actionGet(TimeValue.THIRTY_SECONDS);
        assertThat(model, is(expectedModel));
    }

    private CustomElandModel getCustomElandModel(TaskType taskType) {
        CustomElandModel expectedModel = null;
        if (taskType == TaskType.RERANK) {
            expectedModel = new CustomElandRerankModel(
                randomInferenceEntityId,
                taskType,
                ElasticsearchInternalService.NAME,
                new CustomElandInternalServiceSettings(1, 4, "custom-model", null),
                CustomElandRerankTaskSettings.DEFAULT_SETTINGS,
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
        } else if (taskType == TaskType.TEXT_EMBEDDING) {
            var serviceSettings = new CustomElandInternalTextEmbeddingServiceSettings(1, 4, "custom-model", null);

            expectedModel = new CustomElandEmbeddingModel(
                randomInferenceEntityId,
                taskType,
                ElasticsearchInternalService.NAME,
                serviceSettings,
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
        } else if (taskType == TaskType.SPARSE_EMBEDDING) {
            expectedModel = new CustomElandModel(
                randomInferenceEntityId,
                taskType,
                ElasticsearchInternalService.NAME,
                new CustomElandInternalServiceSettings(1, 4, "custom-model", null),
                EndpointVersions.FIRST_ENDPOINT_VERSION
            );
        }
        return expectedModel;
    }

    public void testBuildInferenceRequest() {
        var id = randomAlphaOfLength(5);
        var inputs = randomList(1, 3, () -> randomAlphaOfLength(4));
        var inputType = randomFrom(InputType.SEARCH, InputType.INGEST);
        var timeout = randomTimeValue();
        var chunk = randomBoolean();
        var request = ElasticsearchInternalService.buildInferenceRequest(
            id,
            TextEmbeddingConfigUpdate.EMPTY_INSTANCE,
            inputs,
            inputType,
            timeout,
            chunk
        );

        assertEquals(id, request.getId());
        assertEquals(inputs, request.getTextInput());
        assertEquals(
            inputType == InputType.INGEST ? TrainedModelPrefixStrings.PrefixType.INGEST : TrainedModelPrefixStrings.PrefixType.SEARCH,
            request.getPrefixType()
        );
        assertEquals(timeout, request.getInferenceTimeout());
        assertEquals(chunk, request.isChunked());
    }

    @SuppressWarnings("unchecked")
    public void testPutModel() {
        var client = mock(Client.class);
        ArgumentCaptor<PutTrainedModelAction.Request> argument = ArgumentCaptor.forClass(PutTrainedModelAction.Request.class);

        doAnswer(invocation -> {
            var listener = (ActionListener<PutTrainedModelAction.Response>) invocation.getArguments()[2];
            listener.onResponse(new PutTrainedModelAction.Response(mock(TrainedModelConfig.class)));
            return null;
        }).when(client).execute(Mockito.same(PutTrainedModelAction.INSTANCE), argument.capture(), any());

        when(client.threadPool()).thenReturn(threadPool);

        var service = createService(client);

        var model = new MultilingualE5SmallModel(
            "my-e5",
            TaskType.TEXT_EMBEDDING,
            "e5",
            new MultilingualE5SmallInternalServiceSettings(1, 1, ".multilingual-e5-small", null),
            EndpointVersions.FIRST_ENDPOINT_VERSION
        );

        service.putModel(model, new ActionListener<>() {
            @Override
            public void onResponse(Boolean success) {
                assertTrue(success);
            }

            @Override
            public void onFailure(Exception e) {
                fail(e);
            }
        });

        var putConfig = argument.getValue().getTrainedModelConfig();
        assertEquals("text_field", putConfig.getInput().getFieldNames().get(0));
    }

    public void testParseRequestConfigEland_SetsDimensionsToOne() {
        var client = mock(Client.class);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            ActionListener<InferModelAction.Response> listener = (ActionListener<InferModelAction.Response>) invocationOnMock
                .getArguments()[2];
            listener.onResponse(
                new InferModelAction.Response(List.of(new MlTextEmbeddingResults("field", new double[] { 0.1 }, false)), "id", true)
            );

            var request = (InferModelAction.Request) invocationOnMock.getArguments()[1];
            assertThat(request.getId(), is("custom-model"));
            return Void.TYPE;
        }).when(client).execute(eq(InferModelAction.INSTANCE), any(), any());
        when(client.threadPool()).thenReturn(threadPool);

        var service = createService(client);

        var serviceSettings = new CustomElandInternalTextEmbeddingServiceSettings(
            1,
            4,
            "custom-model",
            null,
            1,
            SimilarityMeasure.COSINE,
            DenseVectorFieldMapper.ElementType.FLOAT
        );
        var taskType = TaskType.TEXT_EMBEDDING;
        var expectedModel = new CustomElandEmbeddingModel(
            randomInferenceEntityId,
            taskType,
            ElasticsearchInternalService.NAME,
            serviceSettings,
            EndpointVersions.FIRST_ENDPOINT_VERSION
        );

        PlainActionFuture<Model> listener = new PlainActionFuture<>();
        service.checkModelConfig(
            new CustomElandEmbeddingModel(
                randomInferenceEntityId,
                taskType,
                ElasticsearchInternalService.NAME,
                new CustomElandInternalTextEmbeddingServiceSettings(
                    1,
                    4,
                    "custom-model",
                    null,
                    null,
                    SimilarityMeasure.COSINE,
                    DenseVectorFieldMapper.ElementType.FLOAT
                ),
                EndpointVersions.FIRST_ENDPOINT_VERSION
            ),
            listener
        );
        var model = listener.actionGet(TimeValue.THIRTY_SECONDS);
        assertThat(model, is(expectedModel));
    }

    public void testModelVariantDoesNotMatchArchitecturesAndIsNotPlatformAgnostic() {
        {
            var architectures = Set.of("Aarch64");
            assertFalse(
                ElasticsearchInternalService.modelVariantValidForArchitecture(architectures, MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86)
            );

            assertTrue(ElasticsearchInternalService.modelVariantValidForArchitecture(architectures, MULTILINGUAL_E5_SMALL_MODEL_ID));
        }
        {
            var architectures = Set.of("linux-x86_64");
            assertTrue(
                ElasticsearchInternalService.modelVariantValidForArchitecture(architectures, MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86)
            );
            assertTrue(ElasticsearchInternalService.modelVariantValidForArchitecture(architectures, MULTILINGUAL_E5_SMALL_MODEL_ID));
        }
        {
            var architectures = Set.of("linux-x86_64", "Aarch64");
            assertFalse(
                ElasticsearchInternalService.modelVariantValidForArchitecture(architectures, MULTILINGUAL_E5_SMALL_MODEL_ID_LINUX_X86)
            );
            assertTrue(ElasticsearchInternalService.modelVariantValidForArchitecture(architectures, MULTILINGUAL_E5_SMALL_MODEL_ID));
        }
    }

    private ElasticsearchInternalService createService(Client client) {
        var context = new InferenceServiceExtension.InferenceServiceFactoryContext(client, threadPool);
        return new ElasticsearchInternalService(context);
    }

    private ElasticsearchInternalService createService(Client client, Set<String> architectures) {
        var context = new InferenceServiceExtension.InferenceServiceFactoryContext(client, threadPool);
        return new ElasticsearchInternalService(context, l -> l.onResponse(architectures));
    }
}
