#!/bin/bash

# --- CONFIGURATION ---
TENANT_A="a42c9eb9-bf43-4343-8e75-678df86fa38d"
VAULT_A="olymp"

TENANT_B="859269b7-3a13-4f94-9194-abd8992c2582"
VAULT_B="olymp01"
# ---------------------

# 1. Authenticate to Tenant A and fetch secrets
echo "Logging into Tenant A (Source)..."
az login --tenant "$TENANT_A"

echo "Fetching list of secrets from $VAULT_A..."
# Get all secret names in an array
secret_names=$(az keyvault secret list --vault-name "$VAULT_A" --query "[].name" -o tsv)

# Create an associative array in memory to hold the secrets
declare -A secret_store

for name in $secret_names; do
    echo "Retrieving value for: $name"
    # Fetch the plaintext value
    value=$(az keyvault secret show --vault-name "$VAULT_A" --name "$name" --query "value" -o tsv)
    secret_store["$name"]="$value"
done

# 2. Authenticate to Tenant B and write secrets
echo -e "\nLogging into Tenant B (Destination)..."
# Clear previous login tokens to prevent cross-tenant confusion
az logout
az login --tenant "$TENANT_B"

echo "Writing secrets to $VAULT_B..."
for name in "${!secret_store[@]}"; do
    echo "Copying secret: $name"
    az keyvault secret set --vault-name "$VAULT_B" --name "$name" --value "${secret_store[$name]}" > /dev/null
done

echo "Migration complete!"
