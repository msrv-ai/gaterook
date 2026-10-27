# OpenSSH 9.6p1 of Ubuntu 24.04 for connectors/ssh OpenSshIT (test-services/README.md): the user "ubuntu" (uid 1000)
# signs in with a key or a certificate of a CA; procps lets the test see which commands still run.
ARG UBUNTU_TAG=24.04
FROM ubuntu:${UBUNTU_TAG}
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server procps \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /run/sshd \
 && usermod -p '*' ubuntu
COPY entrypoint.sh /entrypoint.sh
EXPOSE 22
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
