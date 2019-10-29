package org.qora.test;

import org.junit.Test;
import org.qora.crypto.CiyamMemoryPoW;

import static org.junit.Assert.*;

import java.util.Random;

public class CiyamMemoryPoWTests {

	@Test
	public void testCompute() {
		Random random = new Random();

		byte[] data = new byte[256];
		random.nextBytes(data);

		int start = 0;
		int range = 1000000;
		int difficulty = 1;

		long startTime = System.currentTimeMillis();
		Integer nonce = CiyamMemoryPoW.compute(data, start, range, difficulty);
		long finishTime = System.currentTimeMillis();

		System.out.println(String.format("Memory-hard PoW took %dms", finishTime - startTime));

		assertNotNull(nonce);

		System.out.println(String.format("nonce: %d", nonce));
	}

	@Test
	public void testMultipleComputes() {
		for (int i = 0; i < 10; ++i)
			testCompute();
	}

}
