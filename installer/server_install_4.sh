#!/bin/bash

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
    echo "Error: $1"
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

if [ $(check_app wget) -ne 0 ] 
then 
    dnf -y install wget 
else
    echo "wget already installed" 
fi

if [ $(check_app screen) -ne 0 ] 
then 
    dnf -y install screen 
else
    echo "screen already installed" 
fi

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
    dnf install -y postgresql12-server

    # Optionally initialize the database and enable automatic start:
    /usr/pgsql-12/bin/postgresql-12-setup initdb
    systemctl enable postgresql-12
    systemctl start postgresql-12
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


echo "Please enter your PostgreSQL user name >>"
read PSQL_USER 

if [ -z "$PSQL_USER" ]
then 
    error_quit "Entered unser name is empty"
fi 

echo "Please enter password for new PostgreSQL user >>"
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
sed -i "/^# IPv4 local connections\:/a${NEW_LINE_POSTGRES}" /var/lib/pgsql/12/data/pg_hba.conf

# Restart postgres

systemctl restart postgresql-12

# Create a Shadow user

useradd -m shadow

# Set up a password for the shadow user

echo "A user named 'shadow' has been created under which main system components will be run. Please enter the password for this user"
passwd shadow

mkdir /home/shadow/shadowserver


# ----- CREDENTIALS -------

./gencreds.sh


# ----- MINIO -------


read -p "Do you want to install Minio now [y/n]? If you don't, you will have to do that manually on this or another machine >> " -n 1 -r
if [[ $REPLY =~ ^[Yy]$ ]]
then
    ./install_minio.sh
fi
