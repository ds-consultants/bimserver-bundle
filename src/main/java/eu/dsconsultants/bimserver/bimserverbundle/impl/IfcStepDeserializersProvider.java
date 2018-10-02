package eu.dsconsultants.bimserver.bimserverbundle.impl;

import org.bimserver.ifc.step.deserializer.IfcStepDeserializer;

interface IfcStepDeserializersProvider {

    IfcStepDeserializer getIfc2x3tc1StepDeserializer();

    IfcStepDeserializer getIfc4StepDeserializer();
}
