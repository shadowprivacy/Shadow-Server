#!/bin/bash

cd /home/shadow/shadowserver

# Create private key for the root CA certificate

echo "Generating the root CA private key..."
openssl genrsa -out rootCA.key 4096

# Create a self-signed root CA certificate

echo "We are going to create a self-signed root CA certificate. Enter the domain name of your server as accessible by your Shadow clients (e.g. shadow.example.com) >>"
read SERVER_DOMAIN
echo "Enter the root CA validity period in days (e.g. 3650 for ten years) >>"
read ROOT_CA_VALIDITY_PERIOD
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

openssl req -x509 -new -nodes -days "$ROOT_CA_VALIDITY_PERIOD" -out rootCA.crt -key rootCA.key -subj "/C=$COUNTRY_NAME/ST=$PROVINCE_NAME/L=$LOCALITY_NAME/O=$ORGANIZATION_NAME/OU=$ORGANIZATIONAL_UNIT/CN=$SERVER_DOMAIN"

# Create the Shadow server private key

echo "Generating the Shadow server private key..."
openssl genrsa -out shadow_a.key 4096

# Create the Minio server private key

echo "Generating the Minio server private key..."
openssl genrsa -out cloud_a.key 4096

# Create the CSR for Shadow

echo "Creating the CSR for the Shadow server..."
openssl req -new -key shadow_a.key -out shadow_a.csr -subj "/C=$COUNTRY_NAME/ST=$PROVINCE_NAME/L=$LOCALITY_NAME/O=$ORGANIZATION_NAME/OU=$ORGANIZATIONAL_UNIT/CN=$SERVER_DOMAIN"

# Create the CSR for Minio

echo "Creating the CSR for the Minio server..."
openssl req -new -key cloud_a.key -out cloud_a.csr -subj "/C=$COUNTRY_NAME/ST=$PROVINCE_NAME/L=$LOCALITY_NAME/O=$ORGANIZATION_NAME/OU=$ORGANIZATIONAL_UNIT/CN=$SERVER_DOMAIN"

# Sign the Shadow certificate with the root CA

echo "Enter the Shadow server certificate validity period in days (e.g. 365 for one year) >>"
read SHADOW_CERT_VALIDITY_PERIOD
openssl x509 -req -in shadow_a.csr -CA rootCA.crt -CAkey rootCA.key -CAcreateserial -days "$SHADOW_CERT_VALIDITY_PERIOD" -out shadow_a.crt -extensions extensions -extfile <(cat <<-EOF
[ extensions ]
basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
subjectAltName=@alt_names
[ alt_names ]
DNS.1 = $SERVER_DOMAIN
EOF
)

# Sign the Minio certificate with the root CA

echo "Enter the Minio server certificate validity period in days (e.g. 365 for one year) >>"
read MINIO_CERT_VALIDITY_PERIOD
openssl x509 -req -in cloud_a.csr -CA rootCA.crt -CAkey rootCA.key -CAcreateserial -days "$MINIO_CERT_VALIDITY_PERIOD" -out cloud_a.crt -extensions extensions -extfile <(cat <<-EOF
[ extensions ]
basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
subjectAltName=@alt_names
[ alt_names ]
DNS.1 = $SERVER_DOMAIN
DNS.2 = localhost
EOF
)

echo "We are going to create the main keystore for the Shadow server. Enter the main keystore password >>"
read SHADOW_STORE_PASS

# Export the Shadow server key and certificate to PKCS12

echo "Exporting to PKCS12..."
openssl pkcs12 -export -password pass:"$SHADOW_STORE_PASS" -in shadow_a.crt -inkey shadow_a.key -out shadow_a.p12 -name shadow_a -CAfile rootCA.crt

# Write the key and certificate to a Java keystore format, so it can be used by dropwizard

echo "Writing the Shadow server key and certificate to the main keystore..."
keytool -importkeystore -srcstoretype PKCS12 -srckeystore shadow_a.p12 -srcstorepass "$SHADOW_STORE_PASS" -destkeystore shadow.keystore -deststorepass "$SHADOW_STORE_PASS"

echo "We are going to create the auxiliary keystore for the Shadow server. Enter the auxiliary keystore password >>"
read AUX_STORE_PASS

# Write the Minio key and certificate to auxiliary.keystore

echo "Writing the Minio server certificate to the auxiliary keystore..."
keytool -importcert -file cloud_a.crt -alias cloud_a -keystore auxiliary.keystore -storepass "$AUX_STORE_PASS" -noprompt

# Add the root CA as trusted

echo "Adding the root CA as trusted in this system..."

cp rootCA.crt /etc/pki/ca-trust/source/anchors
update-ca-trust extract

# Add the Shadow cert as trusted

echo "We are going to add the Shadow server certificate to cacerts. Enter the cacerts password. (In case you never ever changed it, it's 'changeit') >>"
read CACERTS_PASS
keytool -importcert -file shadow_a.crt -alias shadow_a -cacerts -storepass "$CACERTS_PASS"

# Remove files

echo "Cleanup..."

rm -f cloud_a.csr shadow_a.csr shadow_a.p12