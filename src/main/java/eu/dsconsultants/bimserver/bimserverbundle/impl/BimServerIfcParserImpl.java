package eu.dsconsultants.bimserver.bimserverbundle.impl;

import eu.dsconsultants.bimserver.bimserverbundle.BimServerApiException;
import eu.dsconsultants.bimserver.bimserverbundle.BimServerIfcParser;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.ifc.step.deserializer.IfcStepDeserializer;
import org.bimserver.plugins.deserializers.DeserializeException;
import org.bimserver.plugins.renderengine.RenderEngineException;
import org.ifcopenshell.IfcOpenShellEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BimServerIfcParserImpl implements BimServerIfcParser {

    private static final Logger LOG = LoggerFactory.getLogger(BimServerIfcParserImpl.class);
    private final GeomServerExecutablePathProvider geomServerPathProvider;
    private final IfcStepDeserializersProvider deserializersProvider;

    BimServerIfcParserImpl(GeomServerExecutablePathProvider geomServerPathProvider, IfcStepDeserializersProvider deserializersProvider) {
        this.geomServerPathProvider = geomServerPathProvider;
        this.deserializersProvider = deserializersProvider;
    }

    @Override
    public List<org.bimserver.models.ifc2x3tc1.IfcProduct> parseIfc2x3tc1(File file) throws BimServerApiException {
        try {
            IfcStepDeserializer deserializer = deserializersProvider.getIfc2x3tc1StepDeserializer();
            IfcModelInterface model = deserializer.read(file);
            generateGeometry(file, model, true);
            return model.getAllWithSubTypes(org.bimserver.models.ifc2x3tc1.IfcProduct.class);
        } catch (DeserializeException ex) {
            throw new BimServerApiException(ex);
        }
    }

    @Override
    public List<org.bimserver.models.ifc4.IfcProduct> parseIfc4(File file) throws BimServerApiException {
        try {
            IfcStepDeserializer deserializer = deserializersProvider.getIfc4StepDeserializer();
            IfcModelInterface model = deserializer.read(file);
            generateGeometry(file, model, false);
            return model.getAllWithSubTypes(org.bimserver.models.ifc4.IfcProduct.class);
        } catch (DeserializeException ex) {
            throw new BimServerApiException(ex);
        }
    }

    private void generateGeometry(File file, IfcModelInterface model, boolean isIfc2x3tc1) {
        try (IfcOpenShellEngine renderEngine = new IfcOpenShellEngine(geomServerPathProvider.getGeomServerExecutablePath())) {
            renderEngine.init();
            LOG.info("Using executable " + geomServerPathProvider.getGeomServerExecutablePath());

            try (FileInputStream fis = new FileInputStream(file)) {
                AbstractInputStreamGeometryGenerator generator;
                if (isIfc2x3tc1) {
                    generator = new Ifc2x3InputStreamGeometryGenerator(model, fis, renderEngine);
                } else {
                    generator = new Ifc4InputStreamGeometryGenerator(model, fis, renderEngine);
                }
                generator.generateForAllElements();
            }
        } catch (IOException | RenderEngineException ex) {
            LOG.error("Exception during geometry extraction", ex);
        }
    }
}
