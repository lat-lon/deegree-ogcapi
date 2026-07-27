# This file is available under the following license:
# under LGPL 2.1 (LICENSE.TXT) Copyright 2020 Torsten Friebe <tfr@users.sourceforge.net>
FROM tomcat:9-jdk11

ENV LANG=en_US.UTF-8

# add build info labels that can be set on build
# see also https://github.com/opencontainers/image-spec/blob/master/annotations.md
ARG DEEGREE_VERSION=1.3.10
ARG DIALECT=postgres
ARG BUILD_DATE
ARG VCS_REF
ARG VCS_URL
LABEL org.opencontainers.image.created=$BUILD_DATE \
  org.opencontainers.image.source=$VCS_URL \
  org.opencontainers.image.revision=$VCS_REF \
  org.opencontainers.image.version=$DEEGREE_VERSION
LABEL maintainer="deegree TMC <tmc@deegree.org>"

# tomcat port
EXPOSE 8080

# API key to use; if empty will not change API key
ENV DEEGREE_API_KEY=

# add deegree OGCAPI webapp
RUN curl https://repo.deegree.org/content/repositories/public/org/deegree/deegree-ogcapi-webapp-${DIALECT}/${DEEGREE_VERSION}/deegree-ogcapi-webapp-${DIALECT}-${DEEGREE_VERSION}.war -o /tmp/deegree-ogcapi.war

RUN mkdir /usr/local/tomcat/webapps/deegree-ogcapi && \
    cd /usr/local/tomcat/webapps/deegree-ogcapi && \
    jar -xf /tmp/deegree-ogcapi.war && \
    rm /tmp/deegree-ogcapi.war

# apply apikey and run tomcat
CMD ([ -z "$DEEGREE_API_KEY" ] || (echo $DEEGREE_API_KEY > $DEEGREE_WORKSPACE_ROOT/config.apikey)) \
  && /usr/local/tomcat/bin/catalina.sh run