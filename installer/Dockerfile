FROM almalinux:8

RUN dnf install -y make git wget dnf-plugins-core java-11-openjdk java-11-openjdk-devel iproute
RUN wget https://dlcdn.apache.org/maven/maven-3/3.9.4/binaries/apache-maven-3.9.4-bin.tar.gz -P /tmp
RUN tar xf /tmp/apache-maven-3.9.4-bin.tar.gz -C /opt
RUN ln -s /opt/apache-maven-3.9.4 /opt/maven

ENV JAVA_HOME /usr/lib/jvm/jre-openjdk
ENV M2_HOME /opt/maven
ENV MAVEN_HOME /opt/maven
ENV PATH ${M2_HOME}/bin:${PATH}