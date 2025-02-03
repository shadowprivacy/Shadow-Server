#!/bin/bash

. ./lib.sh

NATT_DOMAIN=$1

sed -i "s/=shadow/=${USER_SH}/" coturn.service
cp coturn.service /etc/systemd/system/

printf "\nInstalling coturn..."

dnf -y install coturn

# Setting up the configuration file

echo "Tuning configuration..."

read -p "Is your server behind NAT [y/n]?" -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then
    printf "\n"
    echo "Enter the outside IP address of your server >>"
    read NAT_SERVER_EXTERNAL_IP
    sed -i "s/^#external-ip=60.70.80.92\/172.17.19.102/external-ip=${NAT_SERVER_EXTERNAL_IP}/" /etc/coturn/turnserver.conf
    
    printf "\n"
    echo "Enter the inside IP address of your server >>"
    read NAT_SERVER_IP
    sed -i "s/^#listening-ip=172.17.19.101/listening-ip=${NAT_SERVER_IP}/" /etc/coturn/turnserver.conf 
else
    printf "\nEnter the IP address of your server >>"
    read NAT_SERVER_IP
    sed -i "s/^#listening-ip=172.17.19.101/listening-ip=${NAT_SERVER_IP}/" /etc/coturn/turnserver.conf   
fi

read -p "Do you want to enable STUN (this will make your installation a public STUN server!) [y/n]?" -n 1 -r
if [[ $REPLY =~ ^[Nn]$ ]]
then    
    sed -i "s/^#no-stun/no-stun/" /etc/coturn/turnserver.conf
    sed -i "s/- stun/# - stun/" ${SERVER_PATH}/config/shadow.yml
else
    if test -f ${SERVER_PATH}/config/shadow.yml
    then      
       sed -i "s/stun\:shadow.example.com/stun\:${NATT_DOMAIN}/" ${SERVER_PATH}/config/shadow.yml
    fi       
fi

sed -i "s/^#use-auth-secret/use-auth-secret/" /etc/coturn/turnserver.conf

printf "\nEnter the shared secret for the TURN authorization (should match that configured in the Shadow server) >>"
    read -r TURN_AUTH_SECRET
    
    if [ -z "$TURN_AUTH_SECRET" ]
    then 
        error_quit "Entered secret is empty"
    fi
    
# Update config
    
    TURN_AUTH_SECRET_CONV=$(preproc_sed $(normalize_turn $(preproc_cfg $TURN_AUTH_SECRET)))
        
    sed -i "s/^#static-auth-secret=north/static-auth-secret=${TURN_AUTH_SECRET_CONV}/" /etc/coturn/turnserver.conf
    
    if test -f ${SERVER_PATH}/config/shadow.yml
    then
       TURN_AUTH_SECRET_CONV2=$(preproc_sed $(normalize_yaml $(preproc_cfg $TURN_AUTH_SECRET)))        
       sed -i "s/secret: your_turn_secret/secret\: '${TURN_AUTH_SECRET_CONV2}'/" ${SERVER_PATH}/config/shadow.yml
       sed -i "s/turn\:shadow.example.com/turn\:${NATT_DOMAIN}/" ${SERVER_PATH}/config/shadow.yml
    fi

sed -i "s/^#realm=mycompany.org/realm=${NATT_DOMAIN}/" /etc/coturn/turnserver.conf
sed -i "s/^#no-tcp/no-tcp/" /etc/coturn/turnserver.conf
sed -i "s/^#no-multicast-peers/no-multicast-peers/" /etc/coturn/turnserver.conf
sed -i "s/^#pidfile=\"\/var\/run\/turnserver.pid\"/pidfile=\"\/var\/tmp\/coturn.pid\"/" /etc/coturn/turnserver.conf

# TURNS autoconfig

echo "To ensure better firewall traversal for voice and video calls, TURN over TLS (TURNS) can be automatically configured. This strictly requires that port 80 of the current machine is directly accessible from the Internet or reverse-proxied. If this is not the case, then you can still configure TURNS manually after the installation"
read -p "Do you want to automatically configure TURNS now [y/n]?" -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]   
then
   # RESTRICTED_TLD=("by" "cu" "ir" "kp" "ly" "mm" "ru" "sd" "ss" "su" "sy" "ve" "ye")
   # TLD=$(extract_tld $NATT_DOMAIN)
   
   # if [[ ! " ${RESTRICTED_TLD[*]} " =~ " ${TLD} " ]]
   # then   
       printf "\n"
       echo "Proceeding to request an X.509 certificate. Enter your email address to register your account >>"
       read EMAIL
              
       echo "Installing dependencies..."
       dnf -y install socat       
          
       echo "Opening ports..."
    
       firewall-cmd --zone=public --permanent --add-port=80/tcp    
       firewall-cmd --zone=public --permanent --add-port=5349/tcp
       firewall-cmd --reload
       
       echo "Installing acme.sh..."
       
       cd ${SERVER_PATH}/       
       wget https://raw.githubusercontent.com/acmesh-official/acme.sh/master/acme.sh
       chmod +x acme.sh
              
       ./acme.sh --install --home ${SERVER_PATH}/acme --accountemail ${EMAIL}
       source ~/.bashrc
       
       echo "We are going to use the Google public CA. Please be ready with your EAB credentials. Refer to https://cloud.google.com/certificate-manager/docs/public-ca-tutorial for details"
       printf "\n"
       echo "Enter your EAB key ID >>"
       read EAB_KEY_ID
       printf "\n"
       echo "Enter your EAB HMAC >>"
       read EAB_HMAC
       printf "\n"
       echo "Registering account..."
       ./acme.sh --server google --register-account --accountemail ${EMAIL} --eab-kid ${EAB_KEY_ID} --eab-hmac-key ${EAB_HMAC}
              
       echo "Issuing the certificate..."
       ./acme.sh --issue --standalone --server google -d ${NATT_DOMAIN}  
       
       echo "Updating configs..."       
       
       sed -i "s|^#cert=/etc/pki/coturn/public/turn_server_cert.pem|cert=${SERVER_PATH}/acme/${NATT_DOMAIN}_ecc/fullchain.cer|" /etc/coturn/turnserver.conf
       sed -i "s|^#pkey=/etc/pki/coturn/private/turn_server_pkey.pem|pkey=${SERVER_PATH}/acme/${NATT_DOMAIN}_ecc/${NATT_DOMAIN}.key|" /etc/coturn/turnserver.conf
       
       sed -i "/turn\:${NATT_DOMAIN}:3478/i \ \ \ - turns\:${NATT_DOMAIN}:5349" ${SERVER_PATH}/config/shadow.yml
            
   # else
   # printf  "\nUnfortunately, the top-level domain \"${TLD}\" is not currently supported for automated TURNS configuration. The TURNS configuration will abort. Refer to the documentation to enable TURNS manually.\n"
   # fi    
   
fi

read -p "Do you want to enable CLI access for Coturn [y/n]?" -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then  
    printf "\nEnter the password to be used for CLI access >>"
    read -r CLI_PASSWORD
    
    if [ -z "$CLI_PASSWORD" ]
    then 
        error_quit "Entered password is empty"
    fi     
    
    CLI_PASSWORD_CONV=$(preproc_sed $(normalize_turn $(preproc_cfg $CLI_PASSWORD)))
    
    sed -i "s/^#cli-password=qwerty/cli-password=${CLI_PASSWORD_CONV}/" /etc/coturn/turnserver.conf       
fi

read -p "Do you want to set up custom media port range for Coturn (default is 49152 to 65535) [y/n]?" -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then  
    printf "\nEnter the first port of the range >>"
    read STARTING_PORT    
    sed -i "s/^#min-port=49152/min-port=${STARTING_PORT}/" /etc/coturn/turnserver.conf
    
    printf "\nEnter the last port of the range >>"
    read ENDING_PORT    
    sed -i "s/^#max-port=65535/max-port=${ENDING_PORT}/" /etc/coturn/turnserver.conf
    
    echo "Opening ports..."
    
    firewall-cmd --zone=public --permanent --add-port=3478/udp
    firewall-cmd --zone=public --permanent --add-port=$STARTING_PORT-$ENDING_PORT/udp
    firewall-cmd --reload
else
    echo "Opening ports..."
    
    firewall-cmd --zone=public --permanent --add-port=3478/udp
    firewall-cmd --zone=public --permanent --add-port=49152-65535/udp
    firewall-cmd --reload  
      
fi

echo "Creating Coturn service..."

systemctl daemon-reload
systemctl enable coturn.service

echo "Starting Coturn service..."

systemctl start coturn.service
    