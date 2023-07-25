# Shadow TLS Proxy

Shadow TLS proxy is heavily based on <a href="https://github.com/signalapp/Signal-TLS-Proxy">Signal TLS proxy</a>

To run a Shadow TLS proxy, you will need a host that has ports 80 and 443 accessible from the Internet and a domain name that points to that host. For flexibility of deployment it is fulfilled as a Docker container.

1. Install docker and docker-compose (e.g. `apt update && apt install docker docker-compose`). Refer to <a>https://docs.docker.com/engine/install/</a> for instructions on the variety of operating systems.
1. Ensure your current user has access to docker (`adduser $USER docker`)
1. Copy the proxy module files to your host
1. `./init-certificate.sh`
1. `docker-compose up --detach`

Your proxy is now running! You can share this with the URL `https://proxy.shadowprivacy.com/#<your_host_name>`

## Updating from a previous version

Update the files, then restart your Docker containers:

```
docker-compose down
docker-compose up --detach
```
