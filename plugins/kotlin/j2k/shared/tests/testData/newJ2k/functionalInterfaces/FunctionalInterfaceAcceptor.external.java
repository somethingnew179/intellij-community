class Acceptor {
    private Face face;

    public Face getFace() {
        return face;
    }

    public void setFace(Face face) {
        this.face = face;
    }

    public void acceptFace(Face face) {
    }
}

interface Face {
    void subject(String p);
}