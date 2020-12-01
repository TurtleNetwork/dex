systemctl stop TN-dex.service || true
rm -rf /var/lib/tn-dex/data || true
dpkg -P tn-dex || true
dpkg -i /home/buildagent-matcher/tn-dex*.deb
sed -i "5s/.*/ topic = \"$(uuidgen)\"/" /etc/tn-dex/queue.conf
systemctl start TN-dex
rm -rf /home/buildagent-matcher/*
