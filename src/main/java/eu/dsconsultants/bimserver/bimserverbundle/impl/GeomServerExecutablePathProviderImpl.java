package eu.dsconsultants.bimserver.bimserverbundle.impl;

class GeomServerExecutablePathProviderImpl implements GeomServerExecutablePathProvider {

    private final String path;

    GeomServerExecutablePathProviderImpl(String path) {
        this.path = path;
    }

    @Override
    public String getGeomServerExecutablePath() {
        return path;
    }

}
