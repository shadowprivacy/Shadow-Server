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

function download_sticker_pack
{
    PACK_ID=$1
    PACK_MAX_ID=$2

    mkdir -p ${DATA_PATH}/stickers/$PACK_ID/full

    cd ${DATA_PATH}/stickers/$PACK_ID

    wget https://cdn.signal.org/stickers/$PACK_ID/manifest.proto --no-check-certificate

    cd ${DATA_PATH}/stickers/$PACK_ID/full

    for i in $(seq 1 $PACK_MAX_ID)
       do
          wget https://cdn.signal.org/stickers/$PACK_ID/full/$i --no-check-certificate
       done
}

check_root_and_exit

set +o history

read -p "This will upgrade your Shadow server installation to the version ${SHADOW_SERVER_VERSION}. Are you sure that the Shadow server is stopped and that you have upgraded your configuration file, and the check command reports no errors? [y/n] >> " -n 1 -r
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

printf "\nDownloading new sticker pack..."

download_sticker_pack cfc50156556893ef9838069d3890fe49 23

printf "\nCreating service bucket..."

su -c "${MINIO_PATH}/mc mb shadow/service" - ${USER_SH}
su -c "${MINIO_PATH}/mc policy set download shadow/service" - ${USER_SH}

printf "\nUpdating minio policy..."

MINIO_SERVICE_LOGIN="minio_shadow"

cp -f shadow.json ${MINIO_PATH}
su -c "${MINIO_PATH}/mc admin policy set shadow '' user=${MINIO_SERVICE_LOGIN}" - ${USER_SH}
su -c "${MINIO_PATH}/mc admin policy remove shadow shadow_policy" - ${USER_SH}
su -c "${MINIO_PATH}/mc admin policy add shadow shadow_policy ${MINIO_PATH}/shadow.json" - ${USER_SH}
su -c "${MINIO_PATH}/mc admin policy set shadow shadow_policy user=${MINIO_SERVICE_LOGIN}" - ${USER_SH}

printf "\nPopulating service bucket..."

cd ${DATA_PATH}/service

wget https://check.torproject.org/torbulkexitlist --no-check-certificate
wget https://iptoasn.com/data/ip2asn-v4-u32.tsv.gz

chown -R ${USER_SH} ${DATA_PATH}/service/

echo "To ensure better firewall traversal for voice and video calls, TURN over TLS (TURNS) can be automatically configured. This strictly requires that port 80 of the current machine is directly accessible from the Internet or reverse-proxied. If this is not the case, then you can still configure TURNS manually after the installation"
read -p "Do you want to automatically configure TURNS now [y/n]?" -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]   
then
  chmod +x turns.sh
  ./turns.sh
fi

printf "\nDownloading new Shadow server binary..."

cd ${SERVER_PATH}/

wget https://shadowupdate.sres.su:19080/server/ShadowServer-${SHADOW_SERVER_VERSION}.jar
echo "The SHA256 checksum of the downloaded jar is: "
sha256sum < ShadowServer-${SHADOW_SERVER_VERSION}.jar
read -p "Is that OK [y/n]? >> " -n 1 -r
    if [[ $REPLY =~ ^[Yy]$ ]]
    then
    
    printf "\nPerforming ScyllaDB table markup..."
    
    java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar createaccountsdb ${SERVER_PATH}/config/shadow.yml
    java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar createpushchallengedb ${SERVER_PATH}/config/shadow.yml
    java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar createreportmessagedb ${SERVER_PATH}/config/shadow.yml
    
    chown ${USER_SH} /var/log/shadow.log
    
    else
         error_quit "The jar file has been tampered with!!!"  
    fi    

chown -R ${USER_SH} ${SERVER_PATH}

printf "\nUpgrade complete. Follow the release notes to perform necessary post-installation tasks (if any), then start the Shadow server: 'systemctl start shadow'.\n"

set -o history
