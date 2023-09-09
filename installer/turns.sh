#!/bin/bash

. ./lib.sh

echo "Enter your NAT-T server domain name >>"
read SERVER_DOMAIN
   
RESTRICTED_TLD=("af" "by" "cu" "er" "gn" "ir" "kp" "lr" "ru" "ss" "su" "sy" "zw")
TLD=$(extract_tld $SERVER_DOMAIN)
   
   if [[ ! " ${RESTRICTED_TLD[*]} " =~ " ${TLD} " ]]
   then   
       printf "\n"
       echo "Proceeding to request an X.509 certificate from ZeroSSL. Enter your email address to register with ZeroSSL >>"
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
       
       ./acme.sh --install --home ${SERVER_PATH}/acme --accountemail  ${EMAIL}
       source ~/.bashrc
       ./acme.sh --issue --standalone -d ${SERVER_DOMAIN}  
       
       echo "Updating configs..."       
       
       sed -i "s|^#cert=/usr/local/etc/turn_server_cert.pem|cert=${SERVER_PATH}/acme/${SERVER_DOMAIN}_ecc/fullchain.cer|" /usr/local/etc/turnserver.conf
       sed -i "s|^#pkey=/usr/local/etc/turn_server_pkey.pem|pkey=${SERVER_PATH}/acme/${SERVER_DOMAIN}_ecc/${SERVER_DOMAIN}.key|" /usr/local/etc/turnserver.conf
       
       sed -i "/turn\:${SERVER_DOMAIN}:3478/i \ \ \ - turns\:${SERVER_DOMAIN}:5349" ${SERVER_PATH}/config/shadow.yml
            
   else
   printf  "\nUnfortunately, the top-level domain \"${TLD}\" is not currently supported for automated TURNS configuration. The TURNS configuration will abort. Refer to the documentation to enable TURNS manually.\n"
   fi
   
printf "\nRestarting Coturn now..."
systemctl restart coturn.service  
