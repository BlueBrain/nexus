FROM adoptopenjdk:11-jre-hotspot
LABEL MAINTAINER="Nexus Team <noreply@epfl.ch>"
ENV BLAZEGRAPH_VERSION=2_1_6_RC
ENV JETTY_START_TIMEOUT=120
ENV BLAZEGRAPH_HOME=/var/lib/blazegraph
ENV BLAZEGRAPH_DATA=${BLAZEGRAPH_HOME}/data
ADD https://github.com/blazegraph/database/releases/download/BLAZEGRAPH_${BLAZEGRAPH_VERSION}/blazegraph.jar ${BLAZEGRAPH_HOME}/blazegraph.jar
USER root
RUN {\
    echo "com.bigdata.journal.AbstractJournal.initialExtent=209715200"; \
    echo "com.bigdata.journal.AbstractJournal.maximumExtent=209715200"; \
    echo "com.bigdata.rdf.sail.truthMaintenance=false"; \
    echo "com.bigdata.rdf.store.AbstractTripleStore.axiomsClass=com.bigdata.rdf.axioms.NoAxioms"; \
    echo "com.bigdata.rdf.store.AbstractTripleStore.justify=false"; \
    echo "com.bigdata.rdf.store.AbstractTripleStore.statementIdentifiers=false"; \
    echo "com.bigdata.rdf.store.AbstractTripleStore.textIndex=true"; \
    echo "com.bigdata.journal.AbstractJournal.bufferMode=DiskRW"; \
    echo "com.bigdata.journal.AbstractJournal.file=${BLAZEGRAPH_DATA}/blazegraph.jnl"; \
    echo "com.bigdata.rdf.store.DataLoader.bufferCapacity=100000"; \
    echo "com.bigdata.rdf.store.DataLoader.closure=None"; \
    echo "com.bigdata.rdf.store.DataLoader.ignoreInvalidFiles=true"; \
} > ${BLAZEGRAPH_HOME}/RWStore.properties
RUN {\
      echo "#!/bin/bash"; \
      echo "java -server \${JAVA_OPTS} -Djetty.start.timeout=${JETTY_START_TIMEOUT} -Dbigdata.propertyFile=${BLAZEGRAPH_HOME}/RWStore.properties -jar ${BLAZEGRAPH_HOME}/blazegraph.jar"; \
    } > ${BLAZEGRAPH_HOME}/start.sh
RUN groupadd -r blazegraph  && useradd -r -g blazegraph blazegraph
RUN chown -R blazegraph:0 /var/lib/blazegraph
RUN chmod -R ug+rw /var/lib/blazegraph && chmod +x /var/lib/blazegraph/start.sh
RUN apt-get -qq update
RUN apt-get -yq install dnsutils procps
RUN apt-get clean
USER blazegraph
WORKDIR ${BLAZEGRAPH_HOME}
EXPOSE 9999
ENV NO_PROXY="*" HTTP_PROXY="" HTTPS_PROXY="" no_proxy="*" http_proxy="" https_proxy=""
ENTRYPOINT "${BLAZEGRAPH_HOME}/start.sh"