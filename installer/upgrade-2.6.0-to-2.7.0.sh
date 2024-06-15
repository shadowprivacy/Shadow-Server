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
- your current version of Shadow server is 2.6.0 
- the Shadow server is stopped
- the new jar file is in place
- your default Google service account json file is downloaded, renamed to service-account.json and placed to /home/shadow/shadowserver/config/ 
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

echo "Creating Shadow environment file..."

echo "GOOGLE_APPLICATION_CREDENTIALS=${SERVER_PATH}/config/service-account.json" > /etc/default/shadow_env
chown ${USER_SH} ${SERVER_PATH}/service-account.json

systemctl daemon-reload

printf "\nDownloading new sticker pack..."

download_sticker_pack ccc89a05dc077856b57351e90697976c 23

printf "\nFixing existing sticker packs..."

cd ${DATA_PATH}/stickers/fb535407d2f6497ec074df8b9c51dd1d/full
wget https://cdn.signal.org/stickers/fb535407d2f6497ec074df8b9c51dd1d/full/0 --no-check-certificate
cd ${DATA_PATH}/stickers/9acc9e8aba563d26a4994e69263e3b25/full
wget https://cdn.signal.org/stickers/9acc9e8aba563d26a4994e69263e3b25/full/0 --no-check-certificate
cd ${DATA_PATH}/stickers/e61fa0867031597467ccc036cc65d403/full
wget https://cdn.signal.org/stickers/e61fa0867031597467ccc036cc65d403/full/0 --no-check-certificate
cd ${DATA_PATH}/stickers/cca32f5b905208b7d0f1e17f23fdc185/full
wget https://cdn.signal.org/stickers/cca32f5b905208b7d0f1e17f23fdc185/full/0 --no-check-certificate
cd ${DATA_PATH}/stickers/cfc50156556893ef9838069d3890fe49/full
wget https://cdn.signal.org/stickers/cfc50156556893ef9838069d3890fe49/full/0 --no-check-certificate

printf "\nUpgrade complete. Follow the release notes to perform necessary post-installation tasks (if any), then start the Shadow server: 'systemctl start shadow'.\n"

set -o history
