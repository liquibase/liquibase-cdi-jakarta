package liquibase.integration.jakarta.cdi;


import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.inject.Inject;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.integration.commandline.LiquibaseCommandLineConfiguration;
import liquibase.integration.jakarta.cdi.annotations.LiquibaseType;
import liquibase.logging.Logger;
import liquibase.resource.ResourceAccessor;
import liquibase.util.LiquibaseUtil;
import liquibase.util.NetUtil;

/**
 * A CDI wrapper for Liquibase.
 * <p/>
 * Example Configuration:
 * <p>
 * This CDI configuration example will cause liquibase to run
 * automatically when the CDI container is initialized. It will load
 * <code>db-changelog.xml</code> from the classpath and apply it against
 * <code>myDataSource</code>.
 * </p>
 * 
 * Various producers methods are required to resolve the dependencies, i.e.
 * <pre><code>
 * {@literal @}Dependent
 * public class CDILiquibaseProducer {
 *
 *   {@literal @}Produces {@literal @}LiquibaseType
 *   public CDILiquibaseConfig createConfig() {
 *      CDILiquibaseConfig config = new CDILiquibaseConfig();
 *      config.setChangeLog("liquibase/parser/core/xml/simpleChangeLog.xml");
 *      return config;
 *   }
 *
 *   {@literal @}Produces {@literal @}LiquibaseType
 *   public DataSource createDataSource() throws SQLException {
 *      jdbcDataSource ds = new jdbcDataSource();
 *      ds.setDatabase("jdbc:hsqldb:mem:test");
 *      ds.setUser("sa");
 *      ds.setPassword("");
 *      return ds;
 *   }
 *
 *   {@literal @}Produces {@literal @}LiquibaseType
 *   public ResourceAccessor createResourceAccessor() {
 *      return new ClassLoaderResourceAccessor(getClass().getClassLoader());
 *   }
 *
 * }
 *</code></pre>
 *
 * @author Aaron Walker (http://github.com/aaronwalker), Jeroen Peschier (https://github.com/xazap)
 */
@ApplicationScoped
public class CDILiquibase implements Extension {

    @Inject
    @LiquibaseType
    ResourceAccessor resourceAccessor;

    @Inject
    @LiquibaseType
    protected CDILiquibaseConfig config;

    @Inject
    @LiquibaseType
    private DataSource dataSource;
    private boolean initialized;
    private boolean updateSuccessful;

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isUpdateSuccessful() {
        return updateSuccessful;
    }

    @PostConstruct
    public void onStartup() {
        try {
            Logger log = Scope.getCurrentScope().getLog(getClass());

            log.info("Booting Liquibase " + LiquibaseUtil.getBuildVersionInfo());
            String hostName;
            try {
                hostName = NetUtil.getLocalHostName();
            } catch (Exception e) {
                log.warning("Cannot find hostname: " + e.getMessage());
                log.fine("", e);
                return;
            }

            if (!LiquibaseCommandLineConfiguration.SHOULD_RUN.getCurrentValue()) {
                log.info(String.format("Liquibase did not run on %s because %s was set to false.",
                        hostName,
                        LiquibaseCommandLineConfiguration.SHOULD_RUN.getKey()
                ));
                return;
            }
            if (!config.getShouldRun()) {
                log.info(String.format("Liquibase did not run on %s because CDILiquibaseConfig.shouldRun was set to false.", hostName));
                return;
            }
            initialized = true;
            performUpdate();
        } catch (Throwable e) {
            Scope.getCurrentScope().getLog(getClass()).severe(e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }

            throw new UnexpectedLiquibaseException(e);
        }
    }

    protected void performUpdate() throws LiquibaseException {
        Connection c = null;
        Liquibase liquibase = null;
        try {
            c = dataSource.getConnection();
            liquibase = createLiquibase(c);
            liquibase.update(new Contexts(config.getContexts()), new LabelExpression(config.getLabels()));
            updateSuccessful = true;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        } catch (LiquibaseException ex) {
            updateSuccessful = false;
            throw ex;
        } finally {
            if ((liquibase != null) && (liquibase.getDatabase() != null)) {
                liquibase.getDatabase().close();
            } else if (c != null) {
                try {
                    c.rollback();
                    c.close();
                } catch (SQLException e) {
                    //nothing to do
                }

            }

        }
    }

    @SuppressWarnings("squid:S2095")
    protected Liquibase createLiquibase(Connection c) throws LiquibaseException {
        Liquibase liquibase = new Liquibase(config.getChangeLog(), resourceAccessor, createDatabase(c));
        if (config.getParameters() != null) {
            for (Map.Entry<String, String> entry : config.getParameters().entrySet()) {
                liquibase.setChangeLogParameter(entry.getKey(), entry.getValue());
            }
        }

        if (config.isDropFirst()) {
            liquibase.dropAll();
        }

        return liquibase;
    }

    /**
     * Subclasses may override this method add change some database settings such as
     * default schema before returning the database object.
     *
     * @param c
     * @return a Database implementation retrieved from the {@link DatabaseFactory}.
     * @throws DatabaseException
     */
    protected Database createDatabase(Connection c) throws DatabaseException {
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
        if (config.getDefaultSchema() != null) {
            database.setDefaultSchemaName(config.getDefaultSchema());
        }
        return database;
    }
}
