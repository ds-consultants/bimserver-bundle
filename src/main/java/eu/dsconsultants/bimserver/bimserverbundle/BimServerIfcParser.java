package eu.dsconsultants.bimserver.bimserverbundle;

import java.io.File;
import java.util.List;

public interface BimServerIfcParser {

    List<org.bimserver.models.ifc2x3tc1.IfcProduct> parseIfc2x3tc1(File file) throws BimServerApiException;

    List<org.bimserver.models.ifc4.IfcProduct> parseIfc4(File file) throws BimServerApiException;

}
