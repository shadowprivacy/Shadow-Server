#!/bin/bash

. ./lib.sh

function check_root_and_exit
{
    if [ $(id -u) -ne 0 ] 
    then 
        echo "This script must be run under sudo"
        exit 1
    fi
}

check_root_and_exit

set +o history

read -p "This will upgrade your Shadow server installation to the version ${SHADOW_SERVER_VERSION}. Are you sure that:
- your current version of Shadow server is 2.4.0 
- the Shadow server is stopped
- the new jar file is in place
- you have no important pending accounts (their information will not be preserved during migration)
- the configuration file is upgraded
- the configuration check command reports no errors? [y/n] >> " -n 1 -r
if ! [[ $REPLY =~ ^[Yy]$ ]]
then
    echo " ...Exiting"
    exit 1
fi

sed -i "s/SHADOW_VER/${SHADOW_SERVER_VERSION}/" shadow.service
sed -i "s|/home/shadow/shadowserver|${SERVER_PATH}|" shadow.service
sed -i "s/=shadow/=${USER_SH}/" shadow.service
sed -i "s/shadow bash/${USER_SH} bash/" shadow.service
cp -f shadow.service /etc/systemd/system/

systemctl daemon-reload

# SFU

printf "\n"
read -p "Do you want to install SFU (conferencing server) now [y/n]? If you don't, you will have to do that manually on this or another machine >> " -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then
    chmod +x install_sfu.sh
    printf "\nEnter the domain name of your SFU frontend server as accessible by your Shadow clients (e.g. sfu.example.com) >> "
    read SFU_DOMAIN
     
    echo "We are going to create a self-signed SFU frontend certificate."
    echo "Enter the SFU frontend server certificate validity period in days (e.g. 365 for one year) >>"
    read SFU_CERT_VALIDITY_PERIOD
    echo "Enter your country name (2 letter code) [XX] >>"
    read COUNTRY_NAME
    echo "Enter your state or province name >>"
    read PROVINCE_NAME
    echo "Enter your locality Name (e.g. city) >>"
    read LOCALITY_NAME
    echo "Enter your organization name (e.g. company) >>"
    read ORGANIZATION_NAME
    echo "Enter your organizational unit name (e.g. department)>>"
    read ORGANIZATIONAL_UNIT
     
    ROTATION_ALIAS="sfu_a"
     
    read -p "Do you use certificate rotation [y/n]? >> " -n 1 -r
    if [[ $REPLY =~ ^[Yy]$ ]]
    then
        printf "\n"
        read -p "What is your current certificate rotation alias suffix? Please enter 'a' or 'b'. >> " -n 1 -r 
        if [[ $REPLY =~ ^[Bb]$ ]]
        then
            ROTATION_ALIAS="sfu_b"
        fi
    fi        
                
    # Create the SFU frontend private key

    printf "\nGenerating the SFU frontend private key..."
    openssl genrsa -out ${ROTATION_ALIAS}.key 4096

    # Create the CSR for SFU frontend

    echo "Creating the CSR for the SFU frontend server..."
    openssl req -new -key ${ROTATION_ALIAS}.key -out ${ROTATION_ALIAS}.csr -subj "/C=$COUNTRY_NAME/ST=$PROVINCE_NAME/L=$LOCALITY_NAME/O=$ORGANIZATION_NAME/OU=$ORGANIZATIONAL_UNIT/CN=$SFU_DOMAIN"

    # Sign the SFU frontend certificate with the root CA

    openssl x509 -req -in ${ROTATION_ALIAS}.csr -CA ${SERVER_PATH}/rootCA.crt -CAkey ${SERVER_PATH}/rootCA.key -CAcreateserial -days "$SFU_CERT_VALIDITY_PERIOD" -out ${ROTATION_ALIAS}.crt -extensions extensions -extfile <(cat <<-EOF
[ extensions ]
basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
subjectAltName=@alt_names
[ alt_names ]
DNS.1 = $SFU_DOMAIN
EOF
)
    
    echo "We are going to write the SFU frontend server certificate to the auxiliary keystore. Enter the auxiliary keystore password >>"
    read -r AUX_STORE_PASS

    if [ -z "$AUX_STORE_PASS" ]
    then 
        error_quit "Entered password is empty"
    fi

    echo "Writing the SFU frontend server certificate to the auxiliary keystore..."
    keytool -importcert -file ${ROTATION_ALIAS}.crt -alias ${ROTATION_ALIAS} -keystore ${SERVER_PATH}/auxiliary.keystore -storepass "$AUX_STORE_PASS" -noprompt

    echo "Cleanup..."

    mv ${ROTATION_ALIAS}.crt ${SERVER_PATH} 
    mv ${ROTATION_ALIAS}.key ${SERVER_PATH}
    rm -f ${ROTATION_ALIAS}.csr     
     
   ./install_sfu.sh $SFU_DOMAIN $ROTATION_ALIAS
fi

cd ${SERVER_PATH}/

printf "\nPerforming PostgreSQL accounts db markup..."

java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar accountdb migrate ${SERVER_PATH}/config/shadow.yml
    
printf "\nPerforming ScyllaDB table markup..."
    
java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar createdeletedaccountsdb ${SERVER_PATH}/config/shadow.yml
java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar creatependingsdb ${SERVER_PATH}/config/shadow.yml

printf "\nDropping obsolete ScyllaDB tables..."

printf "\nPlease enter your password for the ScyllaDB superuser 'cassandra' (press Enter to use the default)>>"
read -r CASSANDRA_PASSWORD 

if [ -z "$CASSANDRA_PASSWORD" ]
    then 
    CASSANDRA_PASSWORD="cassandra"
fi
   
echo "DROP KEYSPACE alternator_migrationretrydb;" > scyllatext
echo "DROP KEYSPACE alternator_migrationdeleteddb;" >> scyllatext
cqlsh -u cassandra -p $CASSANDRA_PASSWORD -f scyllatext        

# Cleanup

rm -f scyllatext 

# Directory

printf "\nUpdating directory..."
java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar directory ${SERVER_PATH}/config/shadow.yml

printf "\nUpgrade complete. Follow the release notes to perform necessary post-installation tasks (if any), then start the Shadow server: 'systemctl start shadow'.\n"

set -o history
