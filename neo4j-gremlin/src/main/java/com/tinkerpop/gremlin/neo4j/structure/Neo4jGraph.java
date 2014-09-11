package com.tinkerpop.gremlin.neo4j.structure;

import com.tinkerpop.gremlin.neo4j.process.graph.Neo4jTraversal;
import com.tinkerpop.gremlin.neo4j.process.graph.step.sideEffect.Neo4jGraphStep;
import com.tinkerpop.gremlin.neo4j.process.graph.step.util.Neo4jCypherIterator;
import com.tinkerpop.gremlin.neo4j.process.graph.util.DefaultNeo4jTraversal;
import com.tinkerpop.gremlin.process.computer.GraphComputer;
import com.tinkerpop.gremlin.process.graph.step.sideEffect.StartStep;
import com.tinkerpop.gremlin.structure.Edge;
import com.tinkerpop.gremlin.structure.Graph;
import com.tinkerpop.gremlin.structure.Transaction;
import com.tinkerpop.gremlin.structure.Vertex;
import com.tinkerpop.gremlin.structure.util.ElementHelper;
import com.tinkerpop.gremlin.structure.util.StringFactory;
import com.tinkerpop.gremlin.structure.util.wrapped.WrappedGraph;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationConverter;
import org.neo4j.cypher.javacompat.ExecutionEngine;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.NotInTransactionException;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.HighlyAvailableGraphDatabaseFactory;
import org.neo4j.graphdb.schema.Schema;
import org.neo4j.kernel.GraphDatabaseAPI;
import org.neo4j.kernel.impl.core.NodeManager;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * @author Stephen Mallette (http://stephen.genoprime.com)
 * @author Pieter Martin
 */
@Graph.OptIn(Graph.OptIn.SUITE_STRUCTURE_STANDARD)
@Graph.OptIn(Graph.OptIn.SUITE_PROCESS_STANDARD)
@Graph.OptOut(
        test = "com.tinkerpop.gremlin.process.graph.step.map.MatchTest$JavaMatchTest",
        method = "g_V_matchXa_0sungBy_b__a_0writtenBy_c__b_writtenBy_d__c_sungBy_d__d_hasXname_GarciaXX",
        reason = "This is returning one matched binding that is wrong. This needs to be fixed.")
public class Neo4jGraph implements Graph, WrappedGraph<GraphDatabaseService> {
    private GraphDatabaseService baseGraph;

    public static final String CONFIG_DIRECTORY = "gremlin.neo4j.directory";
    private static final String CONFIG_HA = "gremlin.neo4j.ha";
    private static final String CONFIG_CONF = "gremlin.neo4j.conf";

    private final Neo4jTransaction neo4jTransaction = new Neo4jTransaction();
    private Neo4jGraphVariables neo4jGraphVariables = null;

    protected final TransactionManager transactionManager;
    private final ExecutionEngine cypher;

    private Neo4jGraph(final GraphDatabaseService baseGraph) {
        this.baseGraph = baseGraph;
        this.transactionManager = ((GraphDatabaseAPI) baseGraph).getDependencyResolver().resolveDependency(TransactionManager.class);
        this.cypher = new ExecutionEngine(baseGraph);
    }

    private Neo4jGraph(final Configuration configuration) {
        try {
            final String directory = configuration.getString(CONFIG_DIRECTORY);
            final Map neo4jSpecificConfig = ConfigurationConverter.getMap(configuration.subset(CONFIG_CONF));
            final boolean ha = configuration.getBoolean(CONFIG_HA, false);

            // if HA is enabled then use the correct factory to instantiate the GraphDatabaseService
            this.baseGraph = ha ?
                    new HighlyAvailableGraphDatabaseFactory().newHighlyAvailableDatabaseBuilder(directory).setConfig(neo4jSpecificConfig).newGraphDatabase() :
                    new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(directory).
                            setConfig(neo4jSpecificConfig).newGraphDatabase();
            this.transactionManager = ((GraphDatabaseAPI) baseGraph).getDependencyResolver().resolveDependency(TransactionManager.class);
            this.cypher = new ExecutionEngine(baseGraph);
        } catch (Exception e) {
            if (this.baseGraph != null)
                this.baseGraph.shutdown();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Open a new {@link Neo4jGraph} instance.
     *
     * @param configuration the configuration for the instance
     * @param <G>           the {@link com.tinkerpop.gremlin.structure.Graph} instance
     * @return a newly opened {@link com.tinkerpop.gremlin.structure.Graph}
     */
    public static <G extends Graph> G open(final Configuration configuration) {
        if (null == configuration) throw Graph.Exceptions.argumentCanNotBeNull("configuration");

        if (!configuration.containsKey(CONFIG_DIRECTORY))
            throw new IllegalArgumentException(String.format("Neo4j configuration requires that the %s be set", CONFIG_DIRECTORY));

        return (G) new Neo4jGraph(configuration);
    }

    /**
     * Construct a Neo4jGraph instance by specifying the directory to create the database in..
     */
    public static <G extends Graph> G open(final String directory) {
        final Configuration config = new BaseConfiguration();
        config.setProperty(CONFIG_DIRECTORY, directory);
        return open(config);
    }

    /**
     * Construct a Neo4jGraph instance using an existing Neo4j raw instance.
     */
    public static <G extends Graph> G open(final GraphDatabaseService rawGraph) {
        return (G) new Neo4jGraph(Optional.ofNullable(rawGraph).orElseThrow(() -> Graph.Exceptions.argumentCanNotBeNull("baseGraph")));
    }

    @Override
    public Vertex addVertex(final Object... keyValues) {
        ElementHelper.legalPropertyKeyValueArray(keyValues);
        if (ElementHelper.getIdValue(keyValues).isPresent())
            throw Vertex.Exceptions.userSuppliedIdsNotSupported();

        final String label = ElementHelper.getLabelValue(keyValues).orElse(Vertex.DEFAULT_LABEL);

        this.tx().readWrite();
        final Neo4jVertex vertex = new Neo4jVertex(this.baseGraph.createNode(DynamicLabel.label(label)), this);
        ElementHelper.attachProperties(vertex, keyValues);
        return vertex;
    }

    @Override
    public Neo4jTraversal<Vertex, Vertex> V() {
        this.tx().readWrite();
        final Neo4jTraversal<Vertex, Vertex> traversal = new DefaultNeo4jTraversal<>(this);
        traversal.addStep(new Neo4jGraphStep(traversal, Vertex.class, this));
        return traversal;
    }

    @Override
    public Neo4jTraversal<Edge, Edge> E() {
        this.tx().readWrite();
        final Neo4jTraversal<Edge, Edge> traversal = new DefaultNeo4jTraversal<>(this);
        traversal.addStep(new Neo4jGraphStep(traversal, Edge.class, this));
        return traversal;
    }

    @Override
    public Vertex v(final Object id) {
        this.tx().readWrite();
        if (null == id) throw Graph.Exceptions.elementNotFound(Vertex.class, id);

        try {
            return new Neo4jVertex(this.baseGraph.getNodeById(evaluateToLong(id)), this);
        } catch (final NotFoundException e) {
            throw Graph.Exceptions.elementNotFound(Vertex.class, id);
        } catch (final NumberFormatException e) {
            throw Graph.Exceptions.elementNotFound(Vertex.class, id);
        } catch (final NotInTransactionException e) {
            throw Graph.Exceptions.elementNotFound(Vertex.class, id);
        }
    }

    @Override
    public Edge e(final Object id) {
        this.tx().readWrite();
        if (null == id) throw Graph.Exceptions.elementNotFound(Edge.class, id);

        try {
            return new Neo4jEdge(this.baseGraph.getRelationshipById(evaluateToLong(id)), this);
        } catch (final NotFoundException e) {
            throw Graph.Exceptions.elementNotFound(Edge.class, id);
        } catch (final NumberFormatException e) {
            throw Graph.Exceptions.elementNotFound(Edge.class, id);
        } catch (final NotInTransactionException e) {
            throw Graph.Exceptions.elementNotFound(Edge.class, id);
        }
    }

    @Override
    public <S> Neo4jTraversal<S, S> of() {
        final Neo4jTraversal<S, S> traversal = Neo4jTraversal.of(this);
        traversal.addStep(new StartStep<>(traversal));
        return traversal;
    }

    @Override
    public GraphComputer compute(final Class... graphComputerClass) {
        throw Graph.Exceptions.graphComputerNotSupported();
    }

    @Override
    public Transaction tx() {
        return this.neo4jTransaction;
    }

    @Override
    public Variables variables() {
        if (null == this.neo4jGraphVariables) {
            // TODO: indices as the graph gets larger? but then that sucks to have an index for this... thoughts?
            this.tx().readWrite();
            final Iterator<Map<String, Object>> results = this.cypher.execute("MATCH (n:" + Neo4jGraphVariables.GRAPH_VARIABLE_LABEL.name() + ") RETURN n").iterator();
            this.neo4jGraphVariables = new Neo4jGraphVariables(results.hasNext() ? (Node) results.next().get("n") : this.baseGraph.createNode(Neo4jGraphVariables.GRAPH_VARIABLE_LABEL), this);
        }
        return this.neo4jGraphVariables;
    }

    /**
     * This implementation of {@code close} will also close the current transaction on the the thread, but it
     * is up to the caller to deal with dangling transactions in other threads prior to calling this method.
     */
    @Override
    public void close() throws Exception {
        this.tx().close();
        if (this.baseGraph != null) this.baseGraph.shutdown();
    }

    public String toString() {
        return StringFactory.graphString(this, baseGraph.toString());
    }

    @Override
    public Features features() {
        return new Neo4jGraphFeatures();
    }

    @Override
    public GraphDatabaseService getBaseGraph() {
        return this.baseGraph;
    }

    public Schema getSchema() {
        return this.baseGraph.schema();
    }

    public ExecutionEngine getCypher() {
        return cypher;
    }

    public Neo4jTraversal cypher(final String query) {
        return cypher(query, Collections.emptyMap());
    }

    public Neo4jTraversal cypher(final String query, final Map<String, Object> parameters) {
        this.tx().readWrite();
        final Neo4jTraversal traversal = Neo4jTraversal.of(this);
        traversal.addStep(new StartStep(traversal, new Neo4jCypherIterator(this.cypher.execute(query, parameters).iterator(), this)));
        return traversal;
    }

    private PropertyContainer getGraphProperties() {
        return ((GraphDatabaseAPI) this.baseGraph).getDependencyResolver().resolveDependency(NodeManager.class).getGraphProperties();
    }

    private static Long evaluateToLong(final Object id) throws NumberFormatException {
        Long longId;
        if (id instanceof Long)
            longId = (Long) id;
        else if (id instanceof Number)
            longId = ((Number) id).longValue();
        else
            longId = Double.valueOf(id.toString()).longValue();
        return longId;
    }

    class Neo4jTransaction implements Transaction {
        private Consumer<Transaction> readWriteConsumer;
        private Consumer<Transaction> closeConsumer;

        protected final ThreadLocal<org.neo4j.graphdb.Transaction> threadLocalTx = ThreadLocal.withInitial(() -> null);

        public Neo4jTransaction() {
            // auto transaction behavior
            readWriteConsumer = READ_WRITE_BEHAVIOR.AUTO;

            // commit on close
            closeConsumer = CLOSE_BEHAVIOR.COMMIT;
        }

        @Override
        public void open() {
            if (isOpen())
                throw Transaction.Exceptions.transactionAlreadyOpen();
            else
                threadLocalTx.set(getBaseGraph().beginTx());
        }

        @Override
        public void commit() {
            if (!isOpen())
                return;

            try {
                threadLocalTx.get().success();
            } finally {
                threadLocalTx.get().close();
                threadLocalTx.remove();
            }
        }

        @Override
        public void rollback() {
            if (!isOpen())
                return;

            try {
                javax.transaction.Transaction t = transactionManager.getTransaction();
                if (null == t || t.getStatus() == Status.STATUS_ROLLEDBACK)
                    return;

                threadLocalTx.get().failure();
            } catch (SystemException e) {
                throw new RuntimeException(e);
            } finally {
                threadLocalTx.get().close();
                threadLocalTx.remove();
            }
        }

        @Override
        public <R> Workload<R> submit(final Function<Graph, R> work) {
            return new Workload<>(Neo4jGraph.this, work);
        }

        @Override
        public <G extends Graph> G create() {
            throw Transaction.Exceptions.threadedTransactionsNotSupported();
        }

        @Override
        public boolean isOpen() {
            return (threadLocalTx.get() != null);
        }

        @Override
        public void readWrite() {
            this.readWriteConsumer.accept(this);
        }

        @Override
        public void close() {
            this.closeConsumer.accept(this);
        }

        @Override
        public Transaction onReadWrite(final Consumer<Transaction> consumer) {
            this.readWriteConsumer = Optional.ofNullable(consumer).orElseThrow(Transaction.Exceptions::onReadWriteBehaviorCannotBeNull);
            return this;
        }

        @Override
        public Transaction onClose(final Consumer<Transaction> consumer) {
            this.closeConsumer = Optional.ofNullable(consumer).orElseThrow(Transaction.Exceptions::onCloseBehaviorCannotBeNull);
            return this;
        }
    }

    public static class Neo4jGraphFeatures implements Features {
        @Override
        public GraphFeatures graph() {
            return new GraphFeatures() {
                @Override
                public boolean supportsComputer() {
                    return false;
                }

                @Override
                public VariableFeatures variables() {
                    return new Neo4jGraphVariables.Neo4jVariableFeatures();
                }

                @Override
                public boolean supportsThreadedTransactions() {
                    return false;
                }
            };
        }

        @Override
        public VertexFeatures vertex() {
            return new Neo4jVertexFeatures();
        }

        @Override
        public EdgeFeatures edge() {
            return new Neo4jEdgeFeatures();
        }

        @Override
        public String toString() {
            return StringFactory.featureString(this);
        }

        public static class Neo4jVertexFeatures extends Neo4jElementFeatures implements VertexFeatures {
            @Override
            public VertexPropertyFeatures properties() {
                return new Neo4jVertexPropertyFeatures();
            }
        }

        public static class Neo4jEdgeFeatures extends Neo4jElementFeatures implements EdgeFeatures {
            @Override
            public EdgePropertyFeatures properties() {
                return new Neo4jEdgePropertyFeatures();
            }
        }

        public static class Neo4jElementFeatures implements ElementFeatures {
            @Override
            public boolean supportsUserSuppliedIds() {
                return false;
            }

            @Override
            public boolean supportsStringIds() {
                return false;
            }

            @Override
            public boolean supportsUuidIds() {
                return false;
            }

            @Override
            public boolean supportsAnyIds() {
                return false;
            }

            @Override
            public boolean supportsCustomIds() {
                return false;
            }
        }

        public static class Neo4jVertexPropertyFeatures implements VertexPropertyFeatures {
            @Override
            public boolean supportsMapValues() {
                return false;
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;
            }
        }

        public static class Neo4jEdgePropertyFeatures implements EdgePropertyFeatures {
            @Override
            public boolean supportsMapValues() {
                return false;
            }

            @Override
            public boolean supportsMixedListValues() {
                return false;
            }

            @Override
            public boolean supportsSerializableValues() {
                return false;
            }

            @Override
            public boolean supportsUniformListValues() {
                return false;
            }
        }
    }
}
