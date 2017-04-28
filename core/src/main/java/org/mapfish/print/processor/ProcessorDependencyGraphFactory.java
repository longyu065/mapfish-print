package org.mapfish.print.processor;

import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Objects;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.vividsolutions.jts.util.Assert;

import org.apache.commons.collections.CollectionUtils;
import org.mapfish.print.attribute.HttpRequestHeadersAttribute;
import org.mapfish.print.output.Values;
import org.mapfish.print.parser.HasDefaultValue;
import org.mapfish.print.parser.ParserUtils;
import org.mapfish.print.servlet.MapPrinterServlet;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mapfish.print.parser.ParserUtils.getAllAttributes;

/**
 * Class for constructing {@link org.mapfish.print.processor.ProcessorDependencyGraph} instances.
 * <p></p>
 */
public final class ProcessorDependencyGraphFactory {

    @Autowired
    private MetricRegistry metricRegistry;

    /**
     * External dependencies between processor types.
     */
    @Autowired(required = false)
    private List<ProcessorDependency> dependencies = Lists.newArrayList();

    /**
     * Sets the external dependencies between processors. Usually configured in
     * <code>mapfish-spring-processors.xml</code>
     *
     * @param dependencies the dependencies
     */
    public void setDependencies(final List<ProcessorDependency> dependencies) {
        this.dependencies = dependencies;
    }

    /**
     * Create a {@link ProcessorDependencyGraph}.
     *
     * @param processors the processors that will be part of the graph
     * @param attributes the list of attributes name
     * @return a {@link org.mapfish.print.processor.ProcessorDependencyGraph} constructed from the passed in processors
     */
    @SuppressWarnings("unchecked")
    public ProcessorDependencyGraph build(
            final List<? extends Processor> processors,
            final Set<String> attributes) {
        ProcessorDependencyGraph graph = new ProcessorDependencyGraph();

        final Map<String, ProcessorGraphNode<Object, Object>> provideBy =
                new HashMap<String, ProcessorGraphNode<Object, Object>>();
        for (String attribute : attributes) {
            provideBy.put(attribute, null);
        }

        // Add internal values
        provideBy.put("values", null); // Values.class
        provideBy.put(Values.TASK_DIRECTORY_KEY, null);
        provideBy.put(Values.CLIENT_HTTP_REQUEST_FACTORY_KEY, null);
        provideBy.put(Values.TEMPLATE_KEY, null);
        provideBy.put(Values.PDF_CONFIG, null);
        provideBy.put(Values.SUBREPORT_DIR, null);
        provideBy.put(Values.OUTPUT_FORMAT, null);
        provideBy.put(MapPrinterServlet.JSON_REQUEST_HEADERS, null); // HttpRequestHeadersAttribute.Value.class

        final Map<String, Class<?>> outputTypes = new HashMap<String, Class<?>>();
        final List<ProcessorGraphNode<Object, Object>> nodes =
                new ArrayList<ProcessorGraphNode<Object, Object>>(processors.size());

        for (Processor<Object, Object> processor : processors) {
            final ProcessorGraphNode<Object, Object> node =
                    new ProcessorGraphNode<Object, Object>(processor, this.metricRegistry);

            final Set<InputValue> inputs = getInputs(node);
            boolean isRoot = true;
            // check input/output value dependencies
            for (InputValue input : inputs) {
                final ProcessorGraphNode<Object, Object> solution = provideBy.get(input.getName());
                if (solution != null) {
                    // check that the provided output has the same type
                    final Class<?> inputType = input.getType();
                    final Class<?> outputType = outputTypes.get(input.getName());
                    if (inputType.isAssignableFrom(outputType)) {
                        solution.addDependency(node);
                        isRoot = false;
                    } else {
                        throw new IllegalArgumentException("Type conflict: Processor '" + solution
                                .getName() + "' provides an output with name '" + input.getName() + "' " +
                                "and of type '" + outputType + " ', while processor '" + node.getName()
                                + "' expects an input of that name with type '" + inputType + "'! " +
                                "Please rename one of the attributes in the mappings of the processors.");
                    }
                } else {
                    if (input.getField().getAnnotation(HasDefaultValue.class) == null) {
                        throw new IllegalArgumentException("The Processor '" + processor + "' has no value " +
                                "for the input '" + input.getName() + "'.");
                    }
                }
            }
            if (isRoot) {
                graph.addRoot(node);
            }

            for (OutputValue value : getOutputValues(node)) {
                String outputName = value.getName();
                if (provideBy.containsKey(outputName)) {
                    // there is already an output with the same name
                    if (value.canBeRenamed()) {
                        // if this is just a debug output, we can simply rename it
                        outputName = outputName + "_" + UUID.randomUUID().toString();
                    } else {
                        ProcessorGraphNode<Object, Object> provider = provideBy.get(outputName);
                        if (provider == null) {
                            throw new IllegalArgumentException("Processors '" + processor + " provide the " +
                                    "output '" + outputName + "' who is already declared as an attribute.  " +
                                    "You have to rename one of the outputs and the corresponding input so " +
                                    "that  there is no ambiguity with regards to the input a processor " +
                                    "consumes.");
                        } else {
                            throw new IllegalArgumentException("Multiple processors provide the same output" +
                                    " mapping: '" + processor + "' and '" + provider + "' both provide: '"
                                    + outputName + "'.  You have to rename one of the outputs and the " +
                                    "corresponding input so that  there is no ambiguity with regards to the" +
                                    " input a processor consumes.");
                        }
                    }
                }

                provideBy.put(outputName, node);
                outputTypes.put(outputName, value.getType());
            }
            nodes.add(node);

            // check input/output value dependencies
            for (InputValue input : inputs) {
                if (input.getField().getAnnotation(InputOutputValue.class) != null) {
                    provideBy.put(input.getName(), node);
                }
            }
        }

        ArrayList<ProcessorDependency> allDependencies = Lists.newArrayList(this.dependencies);
        for (ProcessorGraphNode<Object, Object> node : nodes) {
            if (node.getProcessor() instanceof CustomDependencies) {
                CustomDependencies custom = (CustomDependencies) node.getProcessor();
                allDependencies.addAll(custom.createDependencies(nodes));
            }
        }
        final SetMultimap<ProcessorGraphNode<Object, Object>, InputValue> inputsForNodes =
                cacheInputsForNodes(nodes);
        for (ProcessorGraphNode<Object, Object> node : nodes) {
            // check explicit, external dependencies between nodes
            checkExternalDependencies(allDependencies, node, nodes);
        }

        final Collection<? extends Processor> missingProcessors = CollectionUtils.subtract(
                processors, graph.getAllProcessors());
        final StringBuffer missingProcessorsName = new StringBuffer();
        for (Processor p : missingProcessors) {
            missingProcessorsName.append("\n- ");
            missingProcessorsName.append(p.toString());
        }
        Assert.isTrue(
                missingProcessors.isEmpty(),
                "The processor graph:\n" + graph + "\ndoes not contain all the processors, missing:" +
                        missingProcessorsName);

        return graph;
    }

    private void checkExternalDependencies(
            final List<ProcessorDependency> allDependencies,
            final ProcessorGraphNode<Object, Object> node,
            final List<ProcessorGraphNode<Object, Object>> nodes) {
        for (ProcessorDependency dependency : allDependencies) {
            if (dependency.getRequired().equals(node.getProcessor().getClass())) {
                // this node is required by another processor type, let's see if there
                // is an actual processor of this type
                for (ProcessorGraphNode<Object, Object> dependentNode : nodes) {
                    if (dependency.getDependent().equals(dependentNode.getProcessor().getClass())) {
                        // this is the right processor type, let's check if the processors should have
                        // some inputs in common
                        if (dependency.getCommonInputs().isEmpty()) {
                            // no inputs in common required, just create the dependency
                            node.addDependency(dependentNode);
                        } else {
                            // we have to check if the two processors have the given inputs in common.
                            // for example if the input "map" is required, the mapped name for "map" for
                            // processor 1 is retrieved, e.g. "mapDef1". if processor 2 also has a mapped
                            // input with name "mapDef1", we add a dependency.
                            boolean allRequiredInputsInCommon = true;
                            for (String requiredInput : dependency.getCommonInputs()) {
                                // to make things more complicated: the common input attributes might have
                                // different names in the two nodes. e.g. for `CreateOverviewMapProcessor`
                                // the overview map is called `overviewMap`, but on the `SetStyleProcessor`
                                // the map is simply called `map`.
                                final String requiredNodeInput = getRequiredNodeInput(requiredInput);
                                final String dependentNodeInput = getDependentNodeInput(requiredInput);

                                final String mappedKey = getMappedKey(node, requiredNodeInput);
                                if (!getOriginalKey(dependentNode, mappedKey).equals(dependentNodeInput)) {
                                    allRequiredInputsInCommon = false;
                                    break;
                                }
                            }

                            if (allRequiredInputsInCommon) {
                                node.addDependency(dependentNode);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Get the name of the common input attribute for the dependent node.
     *
     * E.g. "map;overviewMap" -> "overviewMap"
     * or   "map" -> "map"
     */
    private String getDependentNodeInput(final String requiredInput) {
        if (!requiredInput.contains(";")) {
            return requiredInput;
        } else {
            return requiredInput.substring(requiredInput.indexOf(";") + 1);
        }
    }

    /**
     * Get the name of the common input attribute for the required node.
     *
     * E.g. "map;overviewMap" -> "map"
     * or   "map" -> "map"
     */
    private String getRequiredNodeInput(final String requiredInput) {
        if (!requiredInput.contains(";")) {
            return requiredInput;
        } else {
            return requiredInput.substring(0, requiredInput.indexOf(";"));
        }
    }

    private String getMappedKey(final ProcessorGraphNode<Object, Object> node, final String requiredInput) {
        String inputName = requiredInput;
        if (node.getInputMapper().containsValue(requiredInput)) {
            inputName = node.getInputMapper().inverse().get(requiredInput);
        }

        return inputName;
    }

    private String getOriginalKey(final ProcessorGraphNode<Object, Object> node, final String mappedKey) {
        String inputName = mappedKey;
        if (node.getInputMapper().containsKey(mappedKey)) {
            inputName = node.getInputMapper().get(mappedKey);
        }

        return inputName;
    }

    private SetMultimap<ProcessorGraphNode<Object, Object>, InputValue> cacheInputsForNodes(
            final List<ProcessorGraphNode<Object, Object>> nodes) {
        final SetMultimap<ProcessorGraphNode<Object, Object>, InputValue> inputsForNodes = HashMultimap.create();
        for (ProcessorGraphNode<Object, Object> node : nodes) {
            final Set<InputValue> inputs = getInputs(node);
            inputsForNodes.putAll(node, inputs);
        }
        return inputsForNodes;
    }

    private boolean hasNoneOrOnlyExternalInput(final ProcessorGraphNode<Object, Object> node, final Set<InputValue> inputs,
            final Map<String, ProcessorGraphNode<Object, Object>> provideBy) {
        if (inputs.isEmpty()) {
            return true;
        }

        for (InputValue input : inputs) {
            final ProcessorGraphNode<Object, Object> provider = provideBy.get(input.getName());
            if (provider != null && provider != node) {
                return false;
            }
        }
        return true;
    }

    private static Set<InputValue> getInputs(final ProcessorGraphNode<Object, Object> node) {
        final BiMap<String, String> inputMapper = node.getInputMapper();
        final Set<InputValue> inputs = Sets.newHashSet();

        final Object inputParameter = node.getProcessor().createInputParameter();
        if (inputParameter != null) {
            verifyAllMappingsMatchParameter(inputMapper.values(), inputParameter.getClass(),
                    "One or more of the input mapping values of '" + node + "'  do not match an input parameter.  The bad mappings are");

            final Collection<Field> allProperties = getAllAttributes(inputParameter.getClass());
            for (Field field : allProperties) {
                String name = ProcessorUtils.getInputValueName(node.getProcessor().getInputPrefix(), inputMapper, field.getName());
                inputs.add(new InputValue(name, field.getType(), field));
            }
        }

        return inputs;
    }

    private static Collection<OutputValue> getOutputValues(final ProcessorGraphNode<Object, Object> node) {
        final Map<String, String> outputMapper = node.getOutputMapper();
        final Set<OutputValue> values = Sets.newHashSet();

        final Set<String> mappings = outputMapper.keySet();
        final Class<?> paramType = node.getProcessor().getOutputType();
        verifyAllMappingsMatchParameter(mappings, paramType, "One or more of the output mapping keys of '" + node + "' do not match an " +
                                                             "output parameter.  The bad mappings are: ");
        final Collection<Field> allProperties = getAllAttributes(paramType);
        for (Field field : allProperties) {
            // if the field is annotated with @DebugValue, it can be renamed automatically in a
            // mapping in case of a conflict.
            final boolean canBeRenamed = field.getAnnotation(InternalValue.class) != null;
            String name = ProcessorUtils.getOutputValueName(node.getProcessor().getOutputPrefix(), outputMapper, field);
            values.add(new OutputValue(name, canBeRenamed, field.getType(), field));
        }

        return values;
    }

    private static void verifyAllMappingsMatchParameter(
            final Set<String> mappings, final Class<?> paramType,
            final String errorMessagePrefix) {
        final Set<String> attributeNames = ParserUtils.getAllAttributeNames(paramType);
        StringBuilder errors = new StringBuilder();
        for (String mapping : mappings) {
            if (!attributeNames.contains(mapping)) {
                errors.append("\n  * ").append(mapping);

            }
        }

        Assert.isTrue(0 == errors.length(), errorMessagePrefix + errors + listOptions(attributeNames) + "\n");
    }

    private static String listOptions(final Set<String> attributeNames) {
        StringBuilder msg = new StringBuilder("\n\nThe possible parameter names are:");
        for (String attributeName : attributeNames) {
            msg.append("\n  * ").append(attributeName);
        }
        return msg.toString();
    }

    private static class InputValue {
        private final String name;
        private final Class<?> type;
        private final Field field;

        public InputValue(final String name, final Class<?> type, final Field field) {
            this.name = name;
            this.type = type;
            this.field = field;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.name);
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj == null) {
                return false;
            }

            if (this.getClass() != obj.getClass()) {
                return false;
            }

            return Objects.equal(this.name, ((InputValue) obj).name);
        }

        public final String getName() {
            return this.name;
        }

        public final Class<?> getType() {
            return this.type;
        }

        public final Field getField() {
            return this.field;
        }

        @Override
        public String toString() {
            return "InputValue{" +
                   "name='" + this.name + "', " +
                   "type=" + this.type.getSimpleName() +
                   '}';
        }
    }

    private static final class OutputValue extends InputValue {
        private final boolean canBeRenamed;

        private OutputValue(
                final String name, final boolean canBeRenamed, final Class<?> type, final Field field) {
            super(name, type, field);
            this.canBeRenamed = canBeRenamed;
        }

        public boolean canBeRenamed() {
            return this.canBeRenamed;
        }
    }
}
