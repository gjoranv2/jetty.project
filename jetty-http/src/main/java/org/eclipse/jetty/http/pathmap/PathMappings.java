//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http.pathmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import org.eclipse.jetty.util.ArrayTernaryTrie;
import org.eclipse.jetty.util.Trie;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Path Mappings of PathSpec to Resource.
 * <p>
 * Sorted into search order upon entry into the Set
 * 
 * @param <E> the type of mapping endpoint
 */
@ManagedObject("Path Mappings")
public class PathMappings<E> implements Iterable<MappedResource<E>>, Dumpable
{
    private static final Logger LOG = Log.getLogger(PathMappings.class);
    private final Set<MappedResource<E>> _mappings = new TreeSet<>();
    
    private Trie<MappedResource<E>> _exactMap=new ArrayTernaryTrie<>(false);
    private Trie<MappedResource<E>> _prefixMap=new ArrayTernaryTrie<>(false);
    private Trie<MappedResource<E>> _suffixMap=new ArrayTernaryTrie<>(false);
    
    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        out.append("PathMappings[size=").append(Integer.toString(_mappings.size())).append("]\n");
        ContainerLifeCycle.dump(out, indent, _mappings);
    }

    @ManagedAttribute(value = "mappings", readonly = true)
    public List<MappedResource<E>> getMappings()
    {
        return new ArrayList<>(_mappings);
    }

    public int size()
    {
        return _mappings.size();
    }
    
    public void reset()
    {
        _mappings.clear();
        _prefixMap.clear();
        _suffixMap.clear();
    }
    
    public void removeIf(Predicate<MappedResource<E>> predicate)
    {
        _mappings.removeIf(predicate);
    }
    
    /**
     * Return a list of MappedResource matches for the specified path.
     * 
     * @param path the path to return matches on
     * @return the list of mapped resource the path matches on
     */
    public List<MappedResource<E>> getMatches(String path)
    {
        boolean isRootPath = "/".equals(path);
        
        List<MappedResource<E>> ret = new ArrayList<>();
        for (MappedResource<E> mr :_mappings)
        {
            switch (mr.getPathSpec().group)
            {
                case ROOT:
                    if (isRootPath)
                        ret.add(mr);
                    break;
                case DEFAULT:
                    if (isRootPath || mr.getPathSpec().matches(path))
                        ret.add(mr);
                    break;
                default:
                    if (mr.getPathSpec().matches(path))
                        ret.add(mr);
                    break;
            }
        }
        return ret;
    }

    public MappedResource<E> getMatch(String path)
    {
        PathSpecGroup last_group=null;
        
        // Search all the mappings
        for (MappedResource<E> mr : _mappings)
        {
            PathSpecGroup group=mr.getPathSpec().getGroup();
            if (group!=last_group)
            {
                // New group in list, so let's look for an optimization
                switch(group)
                {
                    case EXACT:
                    {
                        int i= path.length();
                        final Trie<MappedResource<E>> exact_map=_exactMap;
                        while(i>=0)
                        {
                            MappedResource<E> candidate=exact_map.getBest(path,0,i);
                            if (candidate==null)
                                break;
                            if (candidate.getPathSpec().matches(path))
                                return candidate;
                            i=candidate.getPathSpec().getPrefix().length()-1;
                        }
                        break;
                    }
                        
                    case PREFIX_GLOB:
                    {
                        int i= path.length();
                        final Trie<MappedResource<E>> prefix_map=_prefixMap;
                        while(i>=0)
                        {
                            MappedResource<E> candidate=prefix_map.getBest(path,0,i);
                            if (candidate==null)
                                break;
                            if (candidate.getPathSpec().matches(path))
                                return candidate;
                            i=candidate.getPathSpec().getPrefix().length()-1;
                        }
                        break;
                    }
                        
                    case SUFFIX_GLOB:
                    {
                        int i=0;
                        final Trie<MappedResource<E>> suffix_map=_suffixMap;
                        while ((i=path.indexOf('.',i+1))>0)
                        {
                            MappedResource<E> candidate=suffix_map.get(path,i+1,path.length()-i-1);
                            if (candidate!=null && candidate.getPathSpec().matches(path))
                                return candidate;
                        }
                        break;
                    }
                    
                    default:
                }
            }
            
            if (mr.getPathSpec().matches(path))
                return mr;
            
            last_group=group;
        }
        
        return null;
    }

    @Override
    public Iterator<MappedResource<E>> iterator()
    {
        return _mappings.iterator();
    }

    public static PathSpec asPathSpec(String pathSpecString)
    {
        if ((pathSpecString == null) || (pathSpecString.length() < 1))
        {
            throw new RuntimeException("Path Spec String must start with '^', '/', or '*.': got [" + pathSpecString + "]");
        }
        return pathSpecString.charAt(0) == '^' ? new RegexPathSpec(pathSpecString):new ServletPathSpec(pathSpecString);
    }
    
    public boolean put(String pathSpecString, E resource)
    {
        return put(asPathSpec(pathSpecString),resource);
    }
    
    public boolean put(PathSpec pathSpec, E resource)
    {
        MappedResource<E> entry = new MappedResource<>(pathSpec,resource);
        switch (pathSpec.group)
        {
            case EXACT:
                String exact = pathSpec.getPrefix();
                while (exact!=null && !_exactMap.put(exact,entry))
                    _exactMap=new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedResource<E>>)_exactMap,1.5);
                break;
            case PREFIX_GLOB:
                String prefix = pathSpec.getPrefix();
                while (prefix!=null && !_prefixMap.put(prefix,entry))
                    _prefixMap=new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedResource<E>>)_prefixMap,1.5);
                break;
            case SUFFIX_GLOB:
                String suffix = pathSpec.getSuffix();
                while (suffix!=null && !_suffixMap.put(suffix,entry))
                    _suffixMap=new ArrayTernaryTrie<>((ArrayTernaryTrie<MappedResource<E>>)_prefixMap,1.5);
                break;
            default:
        }
        
        boolean added =_mappings.add(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} to {}",added?"Added":"Ignored",entry,this);
        return added;
    }
    
    @SuppressWarnings("incomplete-switch")
    public boolean remove(PathSpec pathSpec)
    {
        switch (pathSpec.group)
        {
            case EXACT:
                _exactMap.remove(pathSpec.getPrefix());
                break;
            case PREFIX_GLOB:
                _prefixMap.remove(pathSpec.getPrefix());
                break;
            case SUFFIX_GLOB:
                _suffixMap.remove(pathSpec.getSuffix());
                break;
        }
        
        Iterator<MappedResource<E>> iter = _mappings.iterator();
        boolean removed=false;
        while (iter.hasNext())
        {
            if (iter.next().getPathSpec().equals(pathSpec))
            {
                removed=true;
                iter.remove();
                break;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("{} {} to {}",removed?"Removed":"Ignored",pathSpec,this);
        return removed;
    }

    @Override
    public String toString()
    {
        return String.format("%s[size=%d]",this.getClass().getSimpleName(),_mappings.size());
    }

}
