#!/bin/bash

function download_sticker_pack
{
    PACK_ID=$1
    PACK_MAX_ID=$2

    mkdir -p /home/shadow/data/stickers/$PACK_ID/full

    cd /home/shadow/data/stickers/$PACK_ID

    wget https://cdn.signal.org/stickers/$PACK_ID/manifest.proto --no-check-certificate

    cd /home/shadow/data/stickers/$PACK_ID/full

    for i in $(seq 1 $PACK_MAX_ID)
       do
          wget https://cdn.signal.org/stickers/$PACK_ID/full/$i --no-check-certificate
       done
}


# -------- MAIN ------------

SERVER_DOMAIN=$1

mkdir /home/shadow/minio
cp shadow.json /home/shadow/minio/
cp minio.service /etc/systemd/system/
cd /home/shadow/minio

printf "\nDownloading Minio..."
wget https://dl.min.io/server/minio/release/linux-amd64/minio

MINIO_ADMIN_LOGIN="minio_admin"
MINIO_SERVICE_LOGIN="minio_shadow"

echo "Enter the secret key (password) which will be used for Minio admin access >>"
read MINIO_ADMIN_PASSWORD

if [ -z "$MINIO_ADMIN_PASSWORD" ]
    then 
        error_quit "Entered key is empty"
    fi

echo "Enter the secret key (password) which will be used by the Shadow system components to access the Minio service >>"
read MINIO_SERVICE_PASSWORD

if [ -z "$MINIO_SERVICE_PASSWORD" ]
    then 
        error_quit "Entered key is empty"
    fi

# Update config

sed -i "s/accessSecret\: your_service_password/accessSecret\: ${MINIO_SERVICE_PASSWORD}/" /home/shadow/shadowserver/config/shadow.yml

echo "export MINIO_ACCESS_KEY=$MINIO_ADMIN_LOGIN" >> /home/shadow/.bashrc
echo "export MINIO_SECRET_KEY=$MINIO_ADMIN_PASSWORD" >> /home/shadow/.bashrc
echo "export MINIO_BROWSER=off" >> /home/shadow/.bashrc

echo "Copying credentials.."

mkdir -p /home/shadow/.minio/certs/CAs
cp /home/shadow/shadowserver/cloud_a.crt /home/shadow/.minio/certs/public.crt
cp /home/shadow/shadowserver/cloud_a.key /home/shadow/.minio/certs/private.key
cp /home/shadow/shadowserver/rootCA.crt /home/shadow/.minio/certs/CAs/

echo "Opening port 9000..."

firewall-cmd --zone=public --permanent --add-port=9000/tcp
firewall-cmd --reload

echo "Downloading Minio client..."

wget https://dl.min.io/client/mc/release/linux-amd64/mc

mkdir /home/shadow/data
chown -R shadow /home/shadow/data
chown -R shadow /home/shadow/minio 
chown -R shadow /home/shadow/.minio
chmod +x minio
chmod +x mc
mv minio /usr/local/bin/minio
restorecon -rv /usr/local/bin/

echo "Creating Minio environment file..."

echo "MINIO_ACCESS_KEY=$MINIO_ADMIN_LOGIN" > /etc/default/minio_env
echo "MINIO_SECRET_KEY=$MINIO_ADMIN_PASSWORD" >> /etc/default/minio_env
echo "MINIO_BROWSER=off" >> /etc/default/minio_env

echo "Creating Minio service..."

systemctl daemon-reload
systemctl enable minio.service

echo "Starting Minio service..."

systemctl start minio.service

echo "Creating Minio config file..."

sleep 5
su -c "/home/shadow/minio/mc alias set shadow https://localhost:9000 ${MINIO_ADMIN_LOGIN} ${MINIO_ADMIN_PASSWORD}" - shadow

echo "Creating buckets..."

su -c "/home/shadow/minio/mc mb shadow/attachments" - shadow
su -c "/home/shadow/minio/mc mb shadow/profiles" - shadow
su -c "/home/shadow/minio/mc mb shadow/stickers" - shadow
su -c "/home/shadow/minio/mc mb shadow/debuglogs" - shadow

echo "Setting up policies..."

su -c "/home/shadow/minio/mc policy set download shadow/attachments" - shadow
su -c "/home/shadow/minio/mc policy set download shadow/profiles" - shadow
su -c "/home/shadow/minio/mc policy set download shadow/stickers" - shadow
su -c "/home/shadow/minio/mc policy set download shadow/debuglogs" - shadow

echo "Assigning the service user..."

su -c "/home/shadow/minio/mc admin user add shadow ${MINIO_SERVICE_LOGIN} ${MINIO_SERVICE_PASSWORD}" - shadow

echo "Setting up the access policy..."

su -c "/home/shadow/minio/mc admin policy add shadow shadow_policy /home/shadow/minio/shadow.json" - shadow
su -c "/home/shadow/minio/mc admin policy set shadow shadow_policy user=${MINIO_SERVICE_LOGIN}" - shadow

echo "Downloading stickers..."

download_sticker_pack fb535407d2f6497ec074df8b9c51dd1d 25
download_sticker_pack 9acc9e8aba563d26a4994e69263e3b25 24
download_sticker_pack e61fa0867031597467ccc036cc65d403 29
download_sticker_pack cca32f5b905208b7d0f1e17f23fdc185 89

chown -R shadow /home/shadow/data/stickers/

# Update Shadow server config

if test -f /home/shadow/shadowserver/config/shadow.yml
    then      
       sed -i "s/cloudUri\: https\:\/\/shadow.example.com/cloudUri\: ${SERVER_DOMAIN}/" /home/shadow/shadowserver/config/shadow.yml
    fi 







