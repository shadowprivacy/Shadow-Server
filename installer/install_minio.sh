#!/bin/bash

mkdir /home/shadow/minio
cp shadow.json /home/shadow/minio/
cp minio.service /etc/systemd/system/
cd /home/shadow/minio

echo "Downloading Minio..."
wget https://dl.min.io/server/minio/release/linux-amd64/minio

MINIO_ADMIN_LOGIN="minio_admin"
MINIO_SERVICE_LOGIN="minio_shadow"

echo "Enter the secret key (password) which will be used for Minio admin access >>"
read MINIO_ADMIN_PASSWORD

echo "Enter the secret key (password) which will be used by the Shadow system components to access the Minio service >>"
read MINIO_SERVICE_PASSWORD

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

wget https://github.com/oldescole/Shadow-Server/raw/master/stickers.tar.gz

mv stickers.tar.gz /home/shadow/data/stickers/
cd /home/shadow/data/stickers/
tar -zxf stickers.tar.gz
rm -f stickers.tar.gz
chown -R shadow /home/shadow/data/stickers/







