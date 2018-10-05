package eu.dsconsultants.bimserver.bimserverbundle.impl;

import eu.dsconsultants.bimserver.bimserverbundle.BimServerIfcParser;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import org.apache.sling.settings.SlingSettingsService;
import org.bimserver.emf.MetaDataManager;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModuleActivator implements BundleActivator {

    private static final Hashtable<String, Object> EMPTY_PROPERTIES = new Hashtable<>();
    private static final Logger LOG = LoggerFactory.getLogger(ModuleActivator.class);
    private final List<ServiceRegistration> registrations = new ArrayList<>();

    @Override
    public void start(BundleContext bc) throws Exception {
        LOG.info("Initializing MetaDataManager service");
        MetaDataManager metaDataManager = new MetaDataManager(Files.createTempDirectory("org.bimserver.emf.MetaDataManager"));
        metaDataManager.init();
        registrations.add(bc.registerService(MetaDataManager.class, metaDataManager, EMPTY_PROPERTIES));

        LOG.info("Initializing IfcStepDeserializersProvider service");
        IfcStepDeserializersProvider deserializersProvider = new IfcStepDeserializersProviderImpl(metaDataManager);
        registrations.add(bc.registerService(IfcStepDeserializersProvider.class, deserializersProvider, EMPTY_PROPERTIES));

        LOG.info("Initializing IfcGeomServerClient");
        ServiceReference<SlingSettingsService> slingSettingsReference = bc.getServiceReference(SlingSettingsService.class);
        String slingHome = bc.getService(slingSettingsReference).getSlingHomePath();
        bc.ungetService(slingSettingsReference);
        try (DsIfcGeomServerClient geomServerClient = new DsIfcGeomServerClient(DsIfcGeomServerClient.ExecutableSource.GITHUB_RELEASE, Paths.get(slingHome, "ifcgeomserver"))) {
            LOG.info("IfcGeomServerClient executables available: {}", geomServerClient.getExecutableFilename());
            LOG.info("Initializing GeomServerExecutablePathProvider service");
            GeomServerExecutablePathProvider geomServerPathProvider = new GeomServerExecutablePathProviderImpl(geomServerClient.getExecutableFilename());
            registrations.add(bc.registerService(GeomServerExecutablePathProvider.class, geomServerPathProvider, EMPTY_PROPERTIES));

            LOG.info("Initializing BimServerIfcParser service");
            BimServerIfcParser ifcParser = new BimServerIfcParserImpl(geomServerPathProvider, deserializersProvider);
            registrations.add(bc.registerService(BimServerIfcParser.class, ifcParser, EMPTY_PROPERTIES));
        }
    }

    @Override
    public void stop(BundleContext bc) throws Exception {
        LOG.info("Unregistering services");
        registrations.forEach(ServiceRegistration::unregister);
        registrations.clear();
    }

}
