package eu.dsconsultants.bimserver.bimserverbundle.impl;

import java.io.InputStream;
import org.bimserver.emf.IfcModelInterface;
import org.bimserver.models.ifc4.IfcProduct;
import org.bimserver.plugins.renderengine.RenderEngine;

public class Ifc4InputStreamGeometryGenerator extends AbstractInputStreamGeometryGenerator {

    public Ifc4InputStreamGeometryGenerator(IfcModelInterface model, InputStream in, RenderEngine renderEngine) {
        super(model, in, renderEngine);
    }

    @Override
    protected void generateForAllIfcProducts() {
        for (IfcProduct ifcProduct : model.getAllWithSubTypes(IfcProduct.class)) {
            if (ifcProduct.getRepresentation() != null && !ifcProduct.getRepresentation().getRepresentations().isEmpty()) {
                ifcProduct.setGeometry(generateGeometry(ifcProduct.getExpressId()));
            }
        }
    }

}
