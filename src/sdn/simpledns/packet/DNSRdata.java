package sdn.simpledns.packet;

public interface DNSRdata 
{
	public byte[] serialize();
	public int getLength();
}
