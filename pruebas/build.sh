set -e
cd /tmp
R=/home/claude/lector-de-documentos
rm -rf /tmp/lector-cls && mkdir -p /tmp/lector-cls
javac -nowarn -d /tmp/lector-cls $R/app/src/main/java/com/jnieves/lector/office/*.java /home/claude/lector-de-documentos/pruebas/TestMain.java 2>&1 | grep -v "^Note" || true
