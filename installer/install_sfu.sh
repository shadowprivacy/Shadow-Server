#!/bin/bash

. ./lib.sh

SFU_DOMAIN=$1
ROTATION_ALIAS=$2

sed -i "s|/home/shadow/sfu|${SFU_PATH}|" sfu.service
sed -i "s/=shadow/=${USER_SH}/" sfu.service
sed -i "s/shadow bash/${USER_SH} bash/" sfu.service
cp sfu.service /etc/systemd/system/
cp sfu.conf /etc/

printf "\nInstalling necessary packages..."

dnf -y install git cargo policycoreutils-python-utils

# Nginx installation/configuration

printf "\nNginx will be installed to terminate TLS to the SFU\n" 
echo "Enter the port number to be used for TLS termination (should be accessible from the Internet). Press Enter to use the default value of 11084 >>"
read -r TLS_TERMINATION_PORT
printf "\nInstalling nginx..."
dnf -y install nginx

\cp nginx.conf /etc/nginx/
sed -i "s/^user shadow;/user ${USER_SH};/" /etc/nginx/nginx.conf
    
if ! [ -z "$TLS_TERMINATION_PORT" ]
 then 
 sed -i "s/listen 11084 ssl;/listen ${TLS_TERMINATION_PORT} ssl;/" /etc/nginx/nginx.conf
 semanage port -a -t http_port_t -p tcp $TLS_TERMINATION_PORT
else
 semanage port -a -t http_port_t -p tcp 11084
fi

setsebool -P httpd_can_network_connect 1

mkdir /etc/nginx/ssl
mv ${SERVER_PATH}/${ROTATION_ALIAS}.crt /etc/nginx/ssl/sfu.crt
mv ${SERVER_PATH}/${ROTATION_ALIAS}.key /etc/nginx/ssl/sfu.key
restorecon -r /etc/nginx/ssl/  

systemctl enable nginx.service

echo "Starting Nginx service..."
systemctl start nginx.service

# Signal-Calling-Service build

mkdir ${SFU_PATH}
cd ${SFU_PATH}

echo "Downloading Signal-Calling-Service..."

git clone --depth 1 --branch v1.1.0 https://github.com/signalapp/Signal-Calling-Service .

echo "Building Signal-Calling-Service..."

RUSTFLAGS="-C target-cpu=native" cargo build --release

# Configuring SFU parameters

echo "Tuning SFU parameters..."

echo "Enter the RTP/UDP termination IP address of the SFU as accessible from the Internet (i.e. the outside IP adddress) >> "
read MEDIA_IP
sed -i "s/--ice-candidate-ip 192.0.2.1/--ice-candidate-ip ${MEDIA_IP}/" /etc/sfu.conf

echo "Enter the port number for RTP/UDP termination (should be accessible from the Internet). Press Enter to use the default value of 10000 >> "
read MEDIA_PORT
if ! [ -z "$MEDIA_PORT" ]
 then 
 sed -i "s/--ice-candidate-port 10000/--ice-candidate-port ${MEDIA_PORT}/" /etc/sfu.conf
fi

echo "Enter the maximum number of users to be allowed in a conference call. Press Enter to use the default value of 8 >> "
read MAX_USERS
if ! [ -z "$MAX_USERS" ]
 then 
 sed -i "s/--max-clients-per-call 8/--max-clients-per-call ${MAX_USERS}/" /etc/sfu.conf
fi

echo "Enter the default user-requested media rate (kbps). If a user requests nothing, the SFU will assume this value to have been requested. Press Enter to use the default value of 512 >> "
read DEFAULT_REQUESTED_RATE
if ! [ -z "$DEFAULT_REQUESTED_RATE" ]
 then 
 sed -i "s/--default-requested-max-send-rate-kbps 512/--default-requested-max-send-rate-kbps ${DEFAULT_REQUESTED_RATE}/" /etc/sfu.conf
fi

echo "Enter the maximum target send rate for the SFU (kbps). This should align with the bandwidth that you have available. Press Enter to use the default value of 2000 >> "
read MAX_RATE
if ! [ -z "$MAX_RATE" ]
 then 
 sed -i "s/--max-target-send-rate-kbps 2000/--max-target-send-rate-kbps ${MAX_RATE}/" /etc/sfu.conf
fi

echo "Enter the initial target send rate for the SFU (kbps). Press Enter to use the default value of 1000 >> "
read INITIAL_RATE
if ! [ -z "$INITIAL_RATE" ]
 then 
 sed -i "s/--initial-target-send-rate-kbps 1000/--initial-target-send-rate-kbps ${INITIAL_RATE}/" /etc/sfu.conf
fi
 
echo "Opening ports..."
    
firewall-cmd --zone=public --permanent --add-port=$TLS_TERMINATION_PORT/tcp
firewall-cmd --zone=public --permanent --add-port=$MEDIA_PORT/udp
firewall-cmd --reload    

echo "Creating SFU service..."

systemctl daemon-reload
systemctl enable sfu.service

echo "Starting SFU service..."

systemctl start sfu.service

echo "Updating the Shadow config file with SFU info..."
sed -i "s|sfuUri\: https\://sfu.example.com:8080|sfuUri\: https\://${SFU_DOMAIN}:${TLS_TERMINATION_PORT}|" ${SERVER_PATH}/config/shadow.yml
    