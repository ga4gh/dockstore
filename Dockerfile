FROM maven:3.6.2-jdk-11 AS maven
RUN wget https://github.com/mikefarah/yq/releases/download/3.3.2/yq_linux_amd64 \
    && chmod a+x yq_linux_amd64 \
    && mv yq_linux_amd64 /usr/bin/yq

COPY . /
RUN mvn -B clean install -DskipTests -ntp

FROM openjdk:11.0.6-jdk

# Update the APT cache
# prepare for Java download
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
    software-properties-common \
    telnet \
    vim \
    wget \
    locales \
    curl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* \
    && localedef -i en_US -c -f UTF-8 -A /usr/share/locale/locale.alias en_US.UTF-8

ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'

# copy the jar not ending in 's', to make sure we get don't get the one ending in 'sources'
COPY --from=maven /dockstore-webservice/target/dockstore-webservice*[^s].jar /home

# install dockerize
ENV DOCKERIZE_VERSION v0.2.0

RUN curl -L https://github.com/jwilder/dockerize/releases/download/$DOCKERIZE_VERSION/dockerize-linux-amd64-$DOCKERIZE_VERSION.tar.gz \
    | tar -C /usr/local/bin -xzv

RUN mkdir /dockstore_logs && chmod a+rx /dockstore_logs

# Waiting for postgres service
CMD ["dockerize", "-wait", "tcp://postgres:5432", "-timeout", "60s", "/home/init_webservice.sh"]

