package br.edu.ifsp.scl.pipegene.external.persistence;

import br.edu.ifsp.scl.pipegene.domain.*;
import br.edu.ifsp.scl.pipegene.external.persistence.util.JsonUtil;
import br.edu.ifsp.scl.pipegene.usecases.execution.gateway.ExecutionDAO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

@Repository
public class ExecutionDAOImpl implements ExecutionDAO {

    private final JdbcTemplate jdbcTemplate;
    private final JsonUtil jsonUtil;

    @Value("${queries.sql.execution-dao.insert.execution}")
    private String insertExecutionQuery;

    @Value("${queries.sql.execution-dao.insert.execution-steps}")
    private String insertExecutionStepsQuery;

    @Value("${queries.sql.execution-dao.update.execution-by-id}")
    private String updateExecutionByIdQuery;

    @Value("${queries.sql.execution-dao.select.execution-by-id}")
    private String selectExecutionByIdQuery;

    @Value("${queries.sql.execution-dao.select.execution-steps-by-execution-id}")
    private String selectExecutionStepsByExecutionIdQuery;

    @Value("${queries.sql.execution-dao.select.execution-steps-by-execution-ids}")
    private String selectExecutionStepsByExecutionIdsQuery;

    @Value("${queries.sql.execution-dao.select.execution-by-id-and-project-id}")
    private String selectExecutionByIdAndProjectIdQuery;

    @Value("${queries.sql.execution-dao.select.executions-by-project-id}")
    private String selectExecutionsByProjectIdQuery;

    @Value("${queries.sql.execution-dao.exists.execution-id-step-id-provider-id}")
    private String existsExecutionStepProviderQuery;


    public ExecutionDAOImpl(JdbcTemplate jdbcTemplate, JsonUtil jsonUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonUtil = jsonUtil;
    }

    @Override
    public Optional<Execution> findExecutionByProjectIdAndExecutionId(UUID projectId, UUID executionId) {
        Execution execution = jdbcTemplate.queryForObject(selectExecutionByIdAndProjectIdQuery, (rs, rowNum) -> {
            String description = rs.getString("execution_description");
            ExecutionStatusEnum status = ExecutionStatusEnum.valueOf(rs.getString("execution_status"));
            Integer currentStep = rs.getInt("execution_current_step");

            Pipeline pipeline = Pipeline.createWithoutProjectAndSteps(
                    (UUID) rs.getObject("pipeline_id"),
                    rs.getString("pipeline_description")
            );

            Dataset dataset = new Dataset(
                    (UUID) rs.getObject("dataset_id"),
                    rs.getString("dataset_filename")
            );

            return Execution.createWithoutSteps(executionId, pipeline, dataset, description,
                    status, currentStep, null);
        }, executionId, projectId);

        if (Objects.nonNull(execution)) {
            List<ExecutionStep> steps = findExecutionStepsByExecutionId(executionId);
            execution.setSteps(steps);

            return Optional.of(execution);
        }

        return Optional.empty();
    }

    @Override
    public List<Execution> findAllExecutionsByProjectId(UUID projectId) {
        Map<UUID, Execution> executionMap = jdbcTemplate.query(selectExecutionsByProjectIdQuery,
                this::mapperExecutionFromRs, projectId).stream()
                .collect(Collectors.toMap(Execution::getId, Function.identity()));

        Map<UUID, List<ExecutionStep>> steps = findExecutionStepsByExecutionIds(executionMap.keySet()).stream()
                .collect(groupingBy(ExecutionStep::getExecutionId));

        steps.forEach((k, v) -> executionMap.get(k).setSteps(v));

        return new ArrayList<>(executionMap.values());
    }

    @Override
    public Boolean existsExecutionIdAndStepIdForProvider(UUID executionId, UUID stepId, UUID providerId) {
        Boolean exists = jdbcTemplate.queryForObject(existsExecutionStepProviderQuery, Boolean.class,
                executionId, stepId, providerId);
        return Objects.nonNull(exists) && exists;
    }

    @Transactional
    @Override
    public Optional<Execution> findExecutionByExecutionId(UUID executionId) {
        Execution execution = jdbcTemplate.queryForObject(selectExecutionByIdQuery, this::mapperExecutionFromRs,
                executionId);

        if (Objects.nonNull(execution)) {
            List<ExecutionStep> steps = findExecutionStepsByExecutionId(executionId);
            execution.setSteps(steps);

            return Optional.of(execution);
        }
        return Optional.empty();
    }

    private Execution mapperExecutionFromRs(ResultSet rs, int rowNum) throws SQLException {
        UUID executionId = (UUID) rs.getObject("id");

        String description = rs.getString("description");
        String result = rs.getString("result");
        Integer currentStep = rs.getInt("current_step");
        ExecutionStatusEnum status = ExecutionStatusEnum.valueOf(rs.getString("status"));

        UUID pipelineId = (UUID) rs.getObject("pipeline_id");
        String pipelineDescription = rs.getString("pipeline_description");
        Pipeline pipeline = Pipeline.createWithoutProjectAndSteps(pipelineId, pipelineDescription);

        Dataset dataset = new Dataset(
                (UUID) rs.getObject("dataset_id"),
                rs.getString("dataset_filename")
        );
        URI resultURI = Objects.nonNull(result) ? URI.create(result) : null;

        return Execution.createWithoutSteps(executionId, pipeline, dataset, description, status, currentStep, resultURI);
    }

    private List<ExecutionStep> findExecutionStepsByExecutionId(UUID executionId) {
        return jdbcTemplate.query(selectExecutionStepsByExecutionIdQuery, (rs, rowNum) -> {
            try {
                UUID providerId = (UUID) rs.getObject("provider_id");
                String providerName = rs.getString("provider_name");
                String providerDescription = rs.getString("provider_description");
                Provider provider = Provider.createWithPartialValues(providerId, providerName, providerDescription);

                return ExecutionStep.of(
                        (UUID) rs.getObject("id"),
                        executionId,
                        provider,
                        rs.getString("input_type"),
                        rs.getString("output_type"),
                        ExecutionStepState.valueOf(rs.getString("state")),
                        jsonUtil.retrieveStepParams(rs.getString("params")),
                        rs.getInt("step_number")
                );
            } catch (Exception e) {
                e.printStackTrace();
                throw new SQLException();
            }
        }, executionId);
    }

    private List<ExecutionStep> findExecutionStepsByExecutionIds(Collection<UUID> executionIds) {
        Object[] ids = executionIds.toArray();

        return jdbcTemplate.query(selectExecutionStepsByExecutionIdsQuery,
                ps -> ps.setObject(1, ps.getConnection().createArrayOf("uuid", ids)),
                (rs, rowNum) -> {
                    try {
                        UUID providerId = (UUID) rs.getObject("provider_id");
                        String providerName = rs.getString("provider_name");
                        String providerDescription = rs.getString("provider_description");
                        Provider provider = Provider.createWithPartialValues(providerId, providerName, providerDescription);

                        return ExecutionStep.of(
                                (UUID) rs.getObject("id"),
                                (UUID) rs.getObject("execution_id"),
                                provider,
                                rs.getString("input_type"),
                                rs.getString("output_type"),
                                ExecutionStepState.valueOf(rs.getString("state")),
                                jsonUtil.retrieveStepParams(rs.getString("params")),
                                rs.getInt("step_number")
                        );
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw new SQLException();
                    }
                }
        );
    }


    @Transactional
    @Override
    public Execution saveExecution(Execution execution) {
        jdbcTemplate.update(insertExecutionQuery, execution.getId(), execution.getPipeline().getId(),
                execution.getDataset().getId(), execution.getDescription(), execution.getCurrentStep(),
                execution.getStatus().name());

        List<ExecutionStep> steps = execution.getSteps();

        jdbcTemplate.batchUpdate(insertExecutionStepsQuery, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setObject(1, steps.get(i).getId());
                ps.setObject(2, execution.getId());
                ps.setObject(3, steps.get(i).getProvider().getId());
                ps.setString(4, steps.get(i).getInputType());
                ps.setString(5, steps.get(i).getOutputType());
                ps.setObject(6, steps.get(i).getState().name(), Types.OTHER);
                ps.setString(7, jsonUtil.writeMapStringObjectAsJsonString(steps.get(i).getParams()));
                ps.setInt(8, steps.get(i).getStepNumber());
            }

            @Override
            public int getBatchSize() {
                return steps.size();
            }
        });

        return execution;
    }

    @Override
    public void updateExecution(Execution execution) {
        jdbcTemplate.update(updateExecutionByIdQuery, execution.getDescription(), execution.getCurrentStep(), execution.getStatus().name(),
                execution.getId());
        // Update execution_steps

    }
}
