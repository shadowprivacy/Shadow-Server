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

function error_quit 
{
    printf "\nError: $1"
    exit 1
}

function check_app
{
    which "$1" > /dev/null 2> /dev/null
    rs=$?
    if [ $rs -eq 1 ]; 
    then
        echo 1
    else 
        echo 0
    fi
}

# ----- MAIN ------- 

check_root_and_exit

SHADOW_SERVER_VERSION=1.13

sed -i "s/SHADOW_VER/${SHADOW_SERVER_VERSION}/" shadow.service
sed -i "s|/home/shadow/shadowserver|${SERVER_PATH}|" shadow.service
sed -i "s/=shadow/=${USER_SH}/" shadow.service
sed -i "s/shadow bash/${USER_SH} bash/" shadow.service
cp shadow.service /etc/systemd/system/

chmod +x gencreds.sh
chmod +x install_minio.sh
chmod +x install_coturn.sh

if [ $(check_app wget) -ne 0 ] 
then 
    dnf -y install wget 
else
    echo "wget already installed" 
fi

if [ $(check_app tmux) -ne 0 ] 
then 
    dnf -y install tmux 
else
    echo "tmux already installed" 
fi

cp /usr/bin/tmux /usr/local/bin/tmux

# check if java already installed

echo "Installing Java..."
 
if [ $(check_app java) -ne 0 ] 
then 
    dnf -y install java-11-openjdk
    dnf -y install java-11-openjdk-devel
else
    echo "Java already installed" 
    java -version
fi


# check and install PostgreSQL

echo "Installing PostgreSQL..." 

if [ $(check_app psql) -ne 0 ] 
then 
    # Install the repository RPM:
    dnf install -y "https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm"

    # Disable the built-in PostgreSQL module:
    dnf -qy module disable postgresql

    # Install PostgreSQL:
    dnf install -y postgresql13-server

    # Optionally initialize the database and enable automatic start:
    /usr/pgsql-13/bin/postgresql-13-setup initdb
    systemctl enable postgresql-13
    systemctl start postgresql-13
else
    echo "PosgreSQL already installed" 
    psql --version
fi


# how can I check postgre is running? 

#Redis installation

echo "Installing Redis..."
 
if [ $(check_app redis-server) -ne 0 ] 
then 
    dnf -y install epel-release
    dnf -y install redis
    systemctl enable redis
    systemctl start redis
else
    echo "Redis already installed" 
    redis-server --version
fi

PSQL_USER=shadow 

echo "A PostgreSQL user named 'shadow' will be created. Please enter password for this new PostgreSQL user >>"
read PSQL_PASSWORD 

if [ -z "$PSQL_PASSWORD" ]
then 
    error_quit "Entered password is empty"
fi 

# Create postgeSQL user
# Error in case if ${PSQL_USER} already exists 
su -c "psql -c \"CREATE USER ${PSQL_USER} WITH PASSWORD '${PSQL_PASSWORD}';\"" - postgres

# Create PostgreSQL databases

echo "Creating databases..."

su -c "psql -c \"CREATE DATABASE accountdb;\"" - postgres
su -c "psql -c \"CREATE DATABASE messagedb;\"" - postgres
su -c "psql -c \"CREATE DATABASE abusedb;\"" - postgres

# Grant permissions on databases

su -c "psql -c \"GRANT ALL privileges ON DATABASE accountdb TO ${PSQL_USER};\"" - postgres
su -c "psql -c \"GRANT ALL privileges ON DATABASE messagedb TO ${PSQL_USER};\"" - postgres
su -c "psql -c \"GRANT ALL privileges ON DATABASE abusedb TO ${PSQL_USER};\"" - postgres

# Configure authentication

NEW_LINE_POSTGRES="host    all             ${PSQL_USER}        127.0.0.1/32            password"
sed -i "/^# IPv4 local connections\:/a${NEW_LINE_POSTGRES}" /var/lib/pgsql/13/data/pg_hba.conf

# Restart postgres

systemctl restart postgresql-13

# Create a Shadow user

useradd -m ${USER_SH}

# Create the folder structure

mkdir -p ${SERVER_PATH}/license
mkdir ${SERVER_PATH}/config
cp shadow.yml ${SERVER_PATH}/config/

# Request the server domain name

echo "Enter the domain name of your server as accessible by your Shadow clients (e.g. shadow.example.com) >>"
read SERVER_DOMAIN

# ----- CREDENTIALS -------

./gencreds.sh $SERVER_DOMAIN


# ----- MINIO -------


read -p "Do you want to install Minio now [y/n]? If you don't, you will have to do that manually on this or another machine >> " -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then
    ./install_minio.sh $SERVER_DOMAIN
fi

# ----- COTURN -------

read -p "Do you want to install Coturn now [y/n]? If you don't, you will have to do that manually on this or another machine, or use an external (perhaps public) service >> " -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then
    ./install_coturn.sh $SERVER_DOMAIN
fi

# ----- SHADOW SERVER ------

# Update config

sed -i "s/password\: your_postgres_user_password/password\: ${PSQL_PASSWORD}/" ${SERVER_PATH}/config/shadow.yml
sed -i "s|/home/shadow/shadowserver|${SERVER_PATH}|" ${SERVER_PATH}/config/shadow.yml

printf "\n"

read -p "Do you want to download the pre-compiled Shadow server jar file now [y/n]? >> " -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then
    cd ${SERVER_PATH}/
# Download the Shadow-Server jar
    wget https://shadowupdate.sres.su:19080/server/ShadowServer-${SHADOW_SERVER_VERSION}.jar
    echo "The SHA256 checksum of the downloaded jar is: "
    sha256sum < ShadowServer-${SHADOW_SERVER_VERSION}.jar
    read -p "Is that OK [y/n]? >> " -n 1 -r
    if [[ $REPLY =~ ^[Yy]$ ]]
    then
    # Markup the databases
    
    java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar accountdb migrate ${SERVER_PATH}/config/shadow.yml
    java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar messagedb migrate ${SERVER_PATH}/config/shadow.yml
    java -jar ShadowServer-${SHADOW_SERVER_VERSION}.jar abusedb migrate ${SERVER_PATH}/config/shadow.yml
    
    chown ${USER_SH} /var/log/shadow.log
    
    else
         error_quit "The jar file has been tampered with!!!"  
    fi    
fi

# Ensure proper access rights on Shadow folders

chown -R ${USER_SH} ${SERVER_PATH}

# Shadow service

echo "Opening port 8080..."

firewall-cmd --zone=public --permanent --add-port=8080/tcp
firewall-cmd --reload

echo "Creating and enabling Shadow service..."

systemctl daemon-reload
systemctl enable shadow

printf "\nInstallation complete. Follow the Administration Manual to perform necessary post-installation tasks, then start the Shadow server: 'systemctl start shadow'.\n"
