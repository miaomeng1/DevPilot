ALTER TABLE server_node ADD COLUMN listening_tcp_ports LONGTEXT;
ALTER TABLE server_node ADD COLUMN ports_collected_at TIMESTAMP NULL;
