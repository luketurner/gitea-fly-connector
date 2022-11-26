FROM alpine:latest

RUN apk add --no-cache openssh git curl

ADD https://github.com/babashka/babashka/releases/download/v1.0.165/babashka-1.0.165-linux-amd64-static.tar.gz babashka.tar.gz
RUN tar -xf babashka.tar.gz && \
    chmod +x bb && \
    mv bb /usr/local/bin/bb && \
    rm babashka.tar.gz

# Probably a better way to do this.
# Would be nice to get rid of curl dependency but it's used in install.sh too
RUN curl -L https://fly.io/install.sh | FLYCTL_INSTALL=/usr/local sh

ADD gfc.clj /opt/gfc.clj

CMD ["bb", "/opt/gfc.clj"]