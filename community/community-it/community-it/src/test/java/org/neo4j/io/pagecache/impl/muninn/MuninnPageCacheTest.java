/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.impl.muninn;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.OpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.neo4j.io.ByteUnit;
import org.neo4j.io.IOUtils;
import org.neo4j.io.fs.DelegatingFileSystemAbstraction;
import org.neo4j.io.fs.DelegatingStoreChannel;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.memory.ByteBuffers;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCacheTest;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.DelegatingPageCacheTracer;
import org.neo4j.io.pagecache.tracing.EvictionRunEvent;
import org.neo4j.io.pagecache.tracing.MajorFlushEvent;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContext;
import org.neo4j.io.pagecache.tracing.cursor.context.VersionContextSupplier;
import org.neo4j.io.pagecache.tracing.recording.RecordingPageCacheTracer;
import org.neo4j.io.pagecache.tracing.recording.RecordingPageCursorTracer;
import org.neo4j.io.pagecache.tracing.recording.RecordingPageCursorTracer.Fault;

import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.neo4j.io.pagecache.PagedFile.PF_NO_GROW;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_READ_LOCK;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_WRITE_LOCK;
import static org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer.NULL;
import static org.neo4j.io.pagecache.tracing.recording.RecordingPageCacheTracer.Evict;
import static org.neo4j.memory.EmptyMemoryTracker.INSTANCE;

public class MuninnPageCacheTest extends PageCacheTest<MuninnPageCache>
{
    private final long x = 0xCAFEBABEDEADBEEFL;
    private final long y = 0xDECAFC0FFEEDECAFL;
    private MuninnPageCacheFixture fixture;

    @Override
    protected Fixture<MuninnPageCache> createFixture()
    {
        return fixture = new MuninnPageCacheFixture();
    }

    private PageCacheTracer blockCacheFlush( PageCacheTracer delegate )
    {
        fixture.backgroundFlushLatch = new CountDownLatch( 1 );
        return new DelegatingPageCacheTracer( delegate )
        {
            @Override
            public MajorFlushEvent beginCacheFlush()
            {
                try
                {
                    fixture.backgroundFlushLatch.await();
                }
                catch ( InterruptedException e )
                {
                    Thread.currentThread().interrupt();
                }
                return super.beginCacheFlush();
            }
        };
    }

    @Test
    void shouldBeAbleToSetDeleteOnCloseFileAfterItWasMapped() throws IOException
    {
        DefaultPageCacheTracer defaultPageCacheTracer = new DefaultPageCacheTracer();
        File fileForDeletion = file( "fileForDeletion" );
        writeInitialDataTo( fileForDeletion );
        long initialFlushes = defaultPageCacheTracer.flushes();
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, defaultPageCacheTracer ) )
        {
            try ( var cursorTracer = defaultPageCacheTracer.createPageCursorTracer( "shouldBeAbleToSetDeleteOnCloseFileAfterItWasMapped" );
                    PagedFile pagedFile = map( pageCache, fileForDeletion, 8 ) )
            {
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, cursorTracer ) )
                {
                    assertTrue( cursor.next() );
                    cursor.putLong( 0L );
                }
                pagedFile.setDeleteOnClose( true );
            }
            assertFalse( fs.fileExists( fileForDeletion ) );
            assertEquals( 0, defaultPageCacheTracer.flushes() - initialFlushes );
        }
    }

    @Test
    void ableToEvictAllPageInAPageCache() throws IOException
    {
        writeInitialDataTo( file( "a" ) );
        RecordingPageCacheTracer tracer = new RecordingPageCacheTracer();
        RecordingPageCursorTracer cursorTracer = new RecordingPageCursorTracer( tracer, "ableToEvictAllPageInAPageCache" );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, blockCacheFlush( tracer ) );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, cursorTracer ) )
            {
                assertTrue( cursor.next() );
            }
            try ( PageCursor cursor = pagedFile.io( 1, PF_SHARED_READ_LOCK, cursorTracer ) )
            {
                assertTrue( cursor.next() );
            }
            evictAllPages( pageCache );
        }
    }

    @Test
    void mustEvictCleanPageWithoutFlushing() throws Exception
    {
        writeInitialDataTo( file( "a" ) );
        RecordingPageCacheTracer tracer = new RecordingPageCacheTracer();
        RecordingPageCursorTracer cursorTracer = new RecordingPageCursorTracer( tracer, "mustEvictCleanPageWithoutFlushing" );

        try ( MuninnPageCache pageCache = createPageCache( fs, 2, blockCacheFlush( tracer ) );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, cursorTracer ) )
            {
                assertTrue( cursor.next() );
            }
            cursorTracer.reportEvents();
            assertNotNull( cursorTracer.observe( Fault.class ) );
            assertEquals( 1, cursorTracer.faults() );
            assertEquals( 1, tracer.faults() );

            long clockArm = pageCache.evictPages( 1, 1, tracer.beginPageEvictions( 1 ) );
            assertThat( clockArm ).isEqualTo( 1L );
            assertNotNull( tracer.observe( Evict.class ) );
        }
    }

    @Test
    void mustFlushDirtyPagesOnEvictingFirstPage() throws Exception
    {
        writeInitialDataTo( file( "a" ) );
        RecordingPageCacheTracer tracer = new RecordingPageCacheTracer();
        RecordingPageCursorTracer cursorTracer = new RecordingPageCursorTracer( tracer, "mustFlushDirtyPagesOnEvictingFirstPage" );

        try ( MuninnPageCache pageCache = createPageCache( fs, 2, blockCacheFlush( tracer ) );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, cursorTracer ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 0L );
            }
            cursorTracer.reportEvents();
            assertNotNull( cursorTracer.observe( Fault.class ) );
            assertEquals( 1, cursorTracer.faults() );
            assertEquals( 1, tracer.faults() );

            long clockArm = pageCache.evictPages( 1, 0, tracer.beginPageEvictions( 1 ) );
            assertThat( clockArm ).isEqualTo( 1L );
            assertNotNull( tracer.observe( Evict.class ) );

            ByteBuffer buf = readIntoBuffer( "a" );
            assertThat( buf.getLong() ).isEqualTo( 0L );
            assertThat( buf.getLong() ).isEqualTo( y );
        }
    }

    @Test
    void mustFlushDirtyPagesOnEvictingLastPage() throws Exception
    {
        writeInitialDataTo( file( "a" ) );
        RecordingPageCacheTracer tracer = new RecordingPageCacheTracer();
        RecordingPageCursorTracer cursorTracer = new RecordingPageCursorTracer( tracer, "mustFlushDirtyPagesOnEvictingLastPage" );

        try ( MuninnPageCache pageCache = createPageCache( fs, 2, blockCacheFlush( tracer ) );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            try ( PageCursor cursor = pagedFile.io( 1, PF_SHARED_WRITE_LOCK, cursorTracer ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 0L );
            }
            cursorTracer.reportEvents();
            assertNotNull( cursorTracer.observe( Fault.class ) );
            assertEquals( 1, cursorTracer.faults() );
            assertEquals( 1, tracer.faults() );

            long clockArm = pageCache.evictPages( 1, 0, tracer.beginPageEvictions( 1 ) );
            assertThat( clockArm ).isEqualTo( 1L );
            assertNotNull( tracer.observe( Evict.class ) );

            ByteBuffer buf = readIntoBuffer( "a" );
            assertThat( buf.getLong() ).isEqualTo( x );
            assertThat( buf.getLong() ).isEqualTo( 0L );
        }
    }

    @Test
    void mustFlushDirtyPagesOnEvictingAllPages() throws Exception
    {
        writeInitialDataTo( file( "a" ) );
        RecordingPageCacheTracer tracer = new RecordingPageCacheTracer();
        RecordingPageCursorTracer cursorTracer = new RecordingPageCursorTracer( tracer, "mustFlushDirtyPagesOnEvictingAllPages", Fault.class );

        try ( MuninnPageCache pageCache = createPageCache( fs, 4, blockCacheFlush( tracer ) );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK | PF_NO_GROW, cursorTracer ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 0L );
                assertTrue( cursor.next() );
                cursor.putLong( 0L );
                assertFalse( cursor.next() );
            }
            cursorTracer.reportEvents();
            assertNotNull( cursorTracer.observe( Fault.class ) );
            assertNotNull( cursorTracer.observe( Fault.class ) );
            assertEquals( 2, cursorTracer.faults() );
            assertEquals( 2, tracer.faults() );

            long clockArm = pageCache.evictPages( 2, 0, tracer.beginPageEvictions( 2 ) );
            assertThat( clockArm ).isEqualTo( 2L );
            assertNotNull( tracer.observe( Evict.class ) );
            assertNotNull( tracer.observe( Evict.class ) );

            ByteBuffer buf = readIntoBuffer( "a" );
            assertThat( buf.getLong() ).isEqualTo( 0L );
            assertThat( buf.getLong() ).isEqualTo( 0L );
        }
    }

    @Test
    void trackPageModificationTransactionId() throws Exception
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 0 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 7 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 1 );
            }

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                MuninnPageCursor pageCursor = (MuninnPageCursor) cursor;
                assertEquals( 7, pageCursor.pagedFile.getLastModifiedTxId( pageCursor.pinnedPageRef ) );
                assertEquals( 1, cursor.getLong() );
            }
        }
    }

    @Test
    void flushSequentialPagesOnPageFileFlush() throws IOException
    {
        var pageCacheTracer = new DefaultPageCacheTracer();
        try ( MuninnPageCache pageCache = createPageCache( fs, 4, pageCacheTracer );
                PagedFile pagedFile = map( pageCache, file( "a" ), (int) ByteUnit.kibiBytes( 8 ) ) )
        {
            try ( PageCursor cursor = pagedFile.io( 1, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 1 );
            }
            try ( PageCursor cursor = pagedFile.io( 2, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 1 );
            }
            pagedFile.flushAndForce();

            assertEquals( 2, pageCacheTracer.flushes() );
            assertEquals( 1, pageCacheTracer.merges() );
        }
    }

    @Test
    void doNotMergeNonSequentialPageBuffersOnPageFileFlush() throws IOException
    {
        var pageCacheTracer = new DefaultPageCacheTracer();
        try ( MuninnPageCache pageCache = createPageCache( fs, 6, pageCacheTracer );
                PagedFile pagedFile = map( pageCache, file( "a" ), (int) ByteUnit.kibiBytes( 8 ) ) )
        {
            try ( PageCursor cursor = pagedFile.io( 1, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 1 );
            }
            try ( PageCursor cursor = pagedFile.io( 3, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 1 );
            }
            pagedFile.flushAndForce();

            assertEquals( 2, pageCacheTracer.flushes() );
            assertEquals( 0, pageCacheTracer.merges() );
        }
    }

    @Test
    void pageModificationTrackingNoticeWriteFromAnotherThread() throws Exception
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 0 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 7 );

            Future<?> future = executor.submit( () ->
            {
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
                {
                    assertTrue( cursor.next() );
                    cursor.putLong( 1 );
                }
                catch ( IOException e )
                {
                    throw new RuntimeException( e );
                }
            } );
            future.get();

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                MuninnPageCursor pageCursor = (MuninnPageCursor) cursor;
                assertEquals( 7, pageCursor.pagedFile.getLastModifiedTxId( pageCursor.pinnedPageRef ) );
                assertEquals( 1, cursor.getLong() );
            }
        }
    }

    @Test
    void pageModificationTracksHighestModifierTransactionId() throws IOException
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 0 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 1 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 1 );
            }
            cursorContext.initWrite( 12 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 2 );
            }
            cursorContext.initWrite( 7 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 3 );
            }

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                MuninnPageCursor pageCursor = (MuninnPageCursor) cursor;
                assertEquals( 12, pageCursor.pagedFile.getLastModifiedTxId( pageCursor.pinnedPageRef ) );
                assertEquals( 3, cursor.getLong() );
            }
        }
    }

    @Test
    void markCursorContextDirtyWhenRepositionCursorOnItsCurrentPage() throws IOException
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 3 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initRead();
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next( 0 ) );
                assertFalse( cursorContext.isDirty() );

                MuninnPageCursor pageCursor = (MuninnPageCursor) cursor;
                pageCursor.pagedFile.setLastModifiedTxId( ((MuninnPageCursor) cursor).pinnedPageRef, 17 );

                assertTrue( cursor.next( 0 ) );
                assertTrue( cursorContext.isDirty() );
            }
        }
    }

    @Test
    void markCursorContextAsDirtyWhenReadingDataFromMoreRecentTransactions() throws IOException
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 3 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 7 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 3 );
            }

            cursorContext.initRead();
            assertFalse( cursorContext.isDirty() );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                assertEquals( 3, cursor.getLong() );
                assertTrue( cursorContext.isDirty() );
            }
        }
    }

    @Test
    void doNotMarkCursorContextAsDirtyWhenReadingDataFromOlderTransactions() throws IOException
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 23 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 17 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 3 );
            }

            cursorContext.initRead();
            assertFalse( cursorContext.isDirty() );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                assertEquals( 3, cursor.getLong() );
                assertFalse( cursorContext.isDirty() );
            }
        }
    }

    @Test
    void markContextAsDirtyWhenAnyEvictedPageHaveModificationTransactionHigherThenReader() throws IOException
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 5 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 3 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 3 );
            }

            cursorContext.initWrite( 13 );
            try ( PageCursor cursor = pagedFile.io( 1, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 4 );
            }

            evictAllPages( pageCache );

            cursorContext.initRead();
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                assertEquals( 3, cursor.getLong() );
                assertTrue( cursorContext.isDirty() );
            }
        }
    }

    @Test
    void doNotMarkContextAsDirtyWhenAnyEvictedPageHaveModificationTransactionLowerThenReader() throws IOException
    {
        TestVersionContext cursorContext = new TestVersionContext( () -> 15 );
        VersionContextSupplier versionContextSupplier = new ConfiguredVersionContextSupplier( cursorContext );
        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL, versionContextSupplier );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            cursorContext.initWrite( 3 );
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 3 );
            }

            cursorContext.initWrite( 13 );
            try ( PageCursor cursor = pagedFile.io( 1, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                cursor.putLong( 4 );
            }

            evictAllPages( pageCache );

            cursorContext.initRead();
            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_READ_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                assertEquals( 3, cursor.getLong() );
                assertFalse( cursorContext.isDirty() );
            }
        }
    }

    @Test
    void closingTheCursorMustUnlockModifiedPage() throws Exception
    {
        writeInitialDataTo( file( "a" ) );

        try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL );
                PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
        {
            Future<?> task = executor.submit( () ->
            {
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
                {
                    assertTrue( cursor.next() );
                    cursor.putLong( 41 );
                }
                catch ( IOException e )
                {
                    throw new RuntimeException( e );
                }
            } );
            task.get();

            try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
            {
                assertTrue( cursor.next() );
                long value = cursor.getLong();
                cursor.setOffset( 0 );
                cursor.putLong( value + 1 );
            }

            long clockArm = pageCache.evictPages( 1, 0, EvictionRunEvent.NULL );
            assertThat( clockArm ).isEqualTo( 1L );

            ByteBuffer buf = readIntoBuffer( "a" );
            assertThat( buf.getLong() ).isEqualTo( 42L );
            assertThat( buf.getLong() ).isEqualTo( y );
        }
    }

    @Test
    void mustUnblockPageFaultersWhenEvictionGetsException()
    {
        assertTimeoutPreemptively( ofMillis( SEMI_LONG_TIMEOUT_MILLIS ), () ->
        {
            writeInitialDataTo( file( "a" ) );

            MutableBoolean throwException = new MutableBoolean( true );
            FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs )
            {
                @Override
                public StoreChannel open( File fileName, Set<OpenOption> options ) throws IOException
                {
                    return new DelegatingStoreChannel( super.open( fileName, options ) )
                    {
                        @Override
                        public void writeAll( ByteBuffer src, long position ) throws IOException
                        {
                            if ( throwException.booleanValue() )
                            {
                                throw new IOException( "uh-oh..." );
                            }
                            else
                            {
                                super.writeAll( src, position );
                            }
                        }
                    };
                }
            };

            try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL );
                    PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
            {
                // The basic idea is that this loop, which will encounter a lot of page faults, must not block forever even
                // though the eviction thread is unable to flush any dirty pages because the file system throws
                // exceptions on all writes.
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
                {
                    for ( int i = 0; i < 1000; i++ )
                    {
                        assertTrue( cursor.next() );
                    }
                    fail( "Expected an exception at this point" );
                }
                catch ( IOException ignore )
                {
                    // Good.
                }

                throwException.setFalse();
            }
        } );
    }

    @Test
    void pageCacheFlushAndForceMustClearBackgroundEvictionException()
    {
        assertTimeoutPreemptively( ofMillis( SEMI_LONG_TIMEOUT_MILLIS ), () ->
        {
            MutableBoolean throwException = new MutableBoolean( true );
            FileSystemAbstraction fs = new DelegatingFileSystemAbstraction( this.fs )
            {
                @Override
                public StoreChannel open( File fileName, Set<OpenOption> options ) throws IOException
                {
                    return new DelegatingStoreChannel( super.open( fileName, options ) )
                    {
                        @Override
                        public void writeAll( ByteBuffer src, long position ) throws IOException
                        {
                            if ( throwException.booleanValue() )
                            {
                                throw new IOException( "uh-oh..." );
                            }
                            else
                            {
                                super.writeAll( src, position );
                            }
                        }
                    };
                }
            };

            try ( MuninnPageCache pageCache = createPageCache( fs, 2, PageCacheTracer.NULL );
                    PagedFile pagedFile = map( pageCache, file( "a" ), 8 ) )
            {
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
                {
                    assertTrue( cursor.next() ); // Page 0 is now dirty, but flushing it will throw an exception.
                }

                // This will run into that exception, in background eviction:
                pageCache.evictPages( 1, 0, EvictionRunEvent.NULL );

                // We now have a background eviction exception. A successful flushAndForce should clear it, though.
                throwException.setFalse();
                pageCache.flushAndForce();

                // And with a cleared exception, we should be able to work with the page cache without worry.
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
                {
                    for ( int i = 0; i < maxPages * 20; i++ )
                    {
                        assertTrue( cursor.next() );
                    }
                }
            }
        } );
    }

    @Test
    void mustThrowIfMappingFileWouldOverflowReferenceCount()
    {
        assertTimeoutPreemptively( ofMillis( SEMI_LONG_TIMEOUT_MILLIS ), () ->
        {
            File file = file( "a" );
            writeInitialDataTo( file );
            try ( MuninnPageCache pageCache = createPageCache( fs, 30, PageCacheTracer.NULL ) )
            {
                PagedFile pf = null;
                int i = 0;

                try
                {
                    for ( ; i < Integer.MAX_VALUE; i++ )
                    {
                        pf = map( pageCache, file, filePageSize );
                    }
                    fail("Failure was expected");
                }
                catch ( IllegalStateException ile )
                {
                    // expected
                }
                finally
                {
                    for ( int j = 0; j < i; j++ )
                    {
                        try
                        {
                            pf.close();
                        }
                        catch ( Exception e )
                        {
                            //noinspection ThrowFromFinallyBlock
                            throw new AssertionError( "Did not expect pf.close() to throw", e );
                        }
                    }
                }
            }
        } );
    }

    @Test
    void unlimitedShouldFlushInParallel()
    {
        assertTimeoutPreemptively( ofMillis( SEMI_LONG_TIMEOUT_MILLIS ), () ->
        {
            List<File> mappedFiles = new ArrayList<>();
            mappedFiles.add( existingFile( "a" ) );
            mappedFiles.add( existingFile( "b" ) );
            getPageCache( fs, maxPages, new FlushRendezvousTracer( mappedFiles.size() ) );

            List<PagedFile> mappedPagedFiles = new ArrayList<>();
            for ( File mappedFile : mappedFiles )
            {
                PagedFile pagedFile = map( pageCache, mappedFile, filePageSize );
                mappedPagedFiles.add( pagedFile );
                try ( PageCursor cursor = pagedFile.io( 0, PF_SHARED_WRITE_LOCK, NULL ) )
                {
                    assertTrue( cursor.next() );
                    cursor.putInt( 1 );
                }
            }

            pageCache.flushAndForce( IOLimiter.UNLIMITED );

            IOUtils.closeAll( mappedPagedFiles );
        } );
    }

    private static class FlushRendezvousTracer extends DefaultPageCacheTracer
    {
        private final CountDownLatch latch;

        FlushRendezvousTracer( int fileCountToWaitFor )
        {
            latch = new CountDownLatch( fileCountToWaitFor );
        }

        @Override
        public MajorFlushEvent beginFileFlush( PageSwapper swapper )
        {
            latch.countDown();
            try
            {
                latch.await();
            }
            catch ( InterruptedException e )
            {
                e.printStackTrace();
            }
            return MajorFlushEvent.NULL;
        }
    }

    private void evictAllPages( MuninnPageCache pageCache ) throws IOException
    {
        PageList pages = pageCache.pages;
        for ( int pageId = 0; pageId < pages.getPageCount(); pageId++ )
        {
            long pageReference = pages.deref( pageId );
            while ( pages.isLoaded( pageReference ) )
            {
                pages.tryEvict( pageReference, EvictionRunEvent.NULL );
            }
        }
        for ( int pageId = 0; pageId < pages.getPageCount(); pageId++ )
        {
            long pageReference = pages.deref( pageId );
            pageCache.addFreePageToFreelist( pageReference );
        }
    }

    private void writeInitialDataTo( File file ) throws IOException
    {
        try ( StoreChannel channel = fs.write( file ) )
        {
            ByteBuffer buf = ByteBuffers.allocate( 16, INSTANCE );
            buf.putLong( x );
            buf.putLong( y );
            buf.flip();
            channel.writeAll( buf );
        }
    }

    private ByteBuffer readIntoBuffer( String fileName ) throws IOException
    {
        ByteBuffer buffer = ByteBuffers.allocate( 16, INSTANCE );
        try ( StoreChannel channel = fs.read( file( fileName ) ) )
        {
            channel.readAll( buffer );
        }
        buffer.flip();
        return buffer;
    }

    private static class ConfiguredVersionContextSupplier implements VersionContextSupplier
    {

        private final VersionContext versionContext;

        ConfiguredVersionContextSupplier( VersionContext versionContext )
        {
            this.versionContext = versionContext;
        }

        @Override
        public void init( LongSupplier lastClosedTransactionIdSupplier )
        {
        }

        @Override
        public VersionContext getVersionContext()
        {
            return versionContext;
        }
    }

    private static class TestVersionContext implements VersionContext
    {

        private final IntSupplier closedTxIdSupplier;
        private long committingTxId;
        private long lastClosedTxId;
        private boolean dirty;

        TestVersionContext( IntSupplier closedTxIdSupplier )
        {
            this.closedTxIdSupplier = closedTxIdSupplier;
        }

        @Override
        public void initRead()
        {
            this.lastClosedTxId = closedTxIdSupplier.getAsInt();
        }

        @Override
        public void initWrite( long committingTxId )
        {
            this.committingTxId = committingTxId;
        }

        @Override
        public long committingTransactionId()
        {
            return committingTxId;
        }

        @Override
        public long lastClosedTransactionId()
        {
            return lastClosedTxId;
        }

        @Override
        public void markAsDirty()
        {
            dirty = true;
        }

        @Override
        public boolean isDirty()
        {
            return dirty;
        }
    }
}
